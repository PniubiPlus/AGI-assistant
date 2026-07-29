package com.agi.assistant.service.graph;

import com.agi.assistant.config.AppConfig;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * KGStore 在 Neo4jStore 之上封装 RAG 专用的图操作 +
 * 记忆图操作（FOLLOWS / SIMILAR_TO / CAUSES / BELONGS_TO）。
 * 对应 Go internal/graph/kgstore.go
 */
public class KGStore {

    private static final Logger log = LoggerFactory.getLogger(KGStore.class);

    private final Neo4jStore neo4j;
    private final int maxHops;
    private final double kgWeight;
    private final Extractor extractor;

    public KGStore(AppConfig cfg, BiFunction<String, String, String> llmFn) {
        this.neo4j = new Neo4jStore(cfg);
        AppConfig.Neo4jConfig nc = cfg.getNeo4j();
        this.maxHops = nc != null && nc.getMaxHops() > 0 ? nc.getMaxHops() : 2;
        this.kgWeight = nc != null && nc.getWeight() > 0 ? nc.getWeight() : 0.3;
        this.extractor = new Extractor(llmFn);
    }

    public boolean available() { return neo4j.available(); }

    public void close() { neo4j.close(); }

    // ─────────────────────────── 文档摄入 ─────────────────────────────────

    /**
     * 为一批 chunks 抽取实体关系并写入图（异步友好，调用方可包在线程内）。
     */
    public void indexDocument(String docHash, List<ChunkRef> chunks) {
        if (!available()) return;
        for (ChunkRef c : chunks) {
            ExtractResult result = extractor.extract(c.getContent());
            if (result.getEntities().isEmpty()) continue;
            for (Entity ent : result.getEntities()) {
                ent.setDocHash(docHash);
                ent.setChunkId(c.getId());
                upsertEntity(ent);
            }
            for (Relation rel : result.getRelations()) {
                rel.setDocHash(docHash);
                rel.setChunkId(c.getId());
                upsertRelation(rel);
            }
        }
        log.info("知识图谱索引完成: docHash={}, chunks={}", docHash, chunks.size());
    }

    private void upsertEntity(Entity ent) {
        String query = "MERGE (e:Entity {name: $name}) " +
                "SET e.type = $type, e.doc_hash = $doc_hash, e.chunk_id = $chunk_id";
        try (Session s = neo4j.session()) {
            s.run(query, Values.parameters(
                    "name", ent.getName(),
                    "type", ent.getType() != null ? ent.getType().value() : "Unknown",
                    "doc_hash", ent.getDocHash(),
                    "chunk_id", ent.getChunkId()
            ));
        } catch (Exception e) {
            log.warn("Neo4j upsertEntity 失败 ({}): {}", ent.getName(), e.getMessage());
        }
    }

    private void upsertRelation(Relation rel) {
        // 关系类型不可参数化，必须拼入查询，由 isValidRelType 保证安全
        if (!Extractor.isValidRelType(rel.getRelType())) return;
        String query = "MERGE (a:Entity {name: $from}) " +
                "MERGE (b:Entity {name: $to}) " +
                "MERGE (a)-[r:" + rel.getRelType() + " {doc_hash: $doc_hash}]->(b) " +
                "SET r.chunk_id = $chunk_id";
        try (Session s = neo4j.session()) {
            s.run(query, Values.parameters(
                    "from", rel.getFromName(),
                    "to", rel.getToName(),
                    "doc_hash", rel.getDocHash(),
                    "chunk_id", rel.getChunkId()
            ));
        } catch (Exception e) {
            log.warn("Neo4j upsertRelation 失败 ({} -> {}): {}",
                    rel.getFromName(), rel.getToName(), e.getMessage());
        }
    }

    // ─────────────────────────── 文档删除 ─────────────────────────────────

    /**
     * 删除与 docHash 关联的所有关系，并清理孤立实体节点。
     */
    public void deleteDocument(String docHash) {
        if (!available()) return;
        try (Session s = neo4j.session()) {
            s.run("MATCH ()-[r {doc_hash: $doc_hash}]-() DELETE r",
                    Values.parameters("doc_hash", docHash));
            s.run("MATCH (e:Entity) WHERE NOT (e)--() AND e.doc_hash = $doc_hash DELETE e",
                    Values.parameters("doc_hash", docHash));
        } catch (Exception e) {
            log.warn("Neo4j 删除文档失败 ({}): {}", docHash, e.getMessage());
        }
    }

    // ─────────────────────────── 图检索 ───────────────────────────────────

    /**
     * 根据查询文本抽取实体，执行子图遍历（最多 maxHops 跳），返回关联的 ChunkID 列表。
     */
    public List<GraphSearchResult> search(String queryText, int topK) {
        if (!available()) return List.of();
        ExtractResult extracted = extractor.extract(queryText);
        if (extracted.getEntities().isEmpty()) return List.of();

        List<String> names = new ArrayList<>();
        for (Entity e : extracted.getEntities()) names.add(e.getName());

        int hops = maxHops > 0 ? maxHops : 2;

        String cypher = """
                MATCH (e:Entity) WHERE e.name IN $names
                CALL apoc.path.subgraphNodes(e, {
                  maxLevel: $hops,
                  relationshipFilter: "RELATES_TO|PART_OF|CAUSES|DESCRIBES|MENTIONS|WORKS_FOR|LOCATED_IN"
                })
                YIELD node AS neighbor
                WHERE neighbor:Entity AND neighbor.chunk_id IS NOT NULL
                WITH e.name AS seed, neighbor.name AS nb, neighbor.chunk_id AS cid,
                     toInteger(apoc.node.degree(neighbor)) AS degree
                RETURN cid, collect(DISTINCT seed) AS seeds, collect(DISTINCT nb) AS neighbors, max(degree) AS deg
                ORDER BY size(seeds) DESC, deg DESC
                LIMIT $limit""";

        List<GraphSearchResult> results = new ArrayList<>();
        try (Session s = neo4j.session()) {
            Result rs = s.run(cypher, Values.parameters(
                    "names", names,
                    "hops", hops,
                    "limit", topK * 3L
            ));
            Set<Integer> seen = new HashSet<>();
            while (rs.hasNext()) {
                Record rec = rs.next();
                int cid = rec.get("cid").asInt(-1);
                if (cid < 0 || seen.contains(cid)) continue;
                seen.add(cid);
                List<String> seeds = rec.get("seeds").asList(Value::asString);
                List<String> neighbors = rec.get("neighbors").asList(Value::asString);
                long degree = rec.get("deg").asLong(0);
                double score = (seeds.size() * 0.6 + degree * 0.01) * kgWeight;
                results.add(new GraphSearchResult(cid, score, seeds, neighbors));
            }
        } catch (Exception e) {
            // APOC 不可用时降级
            return searchDirect(names, topK);
        }
        results.sort(Comparator.comparingDouble(GraphSearchResult::getScore).reversed());
        if (results.size() > topK) {
            results = new ArrayList<>(results.subList(0, topK));
        }
        return results;
    }

    private List<GraphSearchResult> searchDirect(List<String> names, int topK) {
        List<GraphSearchResult> results = new ArrayList<>();
        try (Session s = neo4j.session()) {
            Result rs = s.run(
                    "MATCH (e:Entity) WHERE e.name IN $names AND e.chunk_id IS NOT NULL " +
                            "RETURN e.chunk_id AS cid, e.name AS name ORDER BY cid LIMIT $limit",
                    Values.parameters("names", names, "limit", (long) topK));
            Set<Integer> seen = new HashSet<>();
            while (rs.hasNext()) {
                Record rec = rs.next();
                int cid = rec.get("cid").asInt(-1);
                String name = rec.get("name").asString("");
                if (cid < 0 || seen.contains(cid)) continue;
                seen.add(cid);
                List<String> ents = new ArrayList<>();
                ents.add(name);
                results.add(new GraphSearchResult(cid, kgWeight, ents, new ArrayList<>()));
            }
        } catch (Exception e) {
            log.warn("Neo4j searchDirect 失败: {}", e.getMessage());
        }
        return results;
    }

    // ─────────────────────────── 记忆图操作 ────────────────────────────────

    public void upsertMemoryNode(int memId, String content, double importance) {
        if (!available()) return;
        try (Session s = neo4j.session()) {
            s.run("MERGE (m:Memory {mem_id: $id}) SET m.content = $content, m.importance = $importance",
                    Values.parameters("id", (long) memId, "content", content, "importance", importance));
        } catch (Exception e) {
            log.warn("Neo4j UpsertMemoryNode 失败 (id={}): {}", memId, e.getMessage());
        }
    }

    /**
     * edgeType: FOLLOWS | SIMILAR_TO | CAUSES | BELONGS_TO
     */
    public void addMemoryEdge(int fromId, int toId, String edgeType, double weight) {
        if (!available()) return;
        if (!isValidMemoryEdge(edgeType)) return;
        String query = "MATCH (a:Memory {mem_id: $from}), (b:Memory {mem_id: $to}) " +
                "MERGE (a)-[r:" + edgeType + "]->(b) SET r.weight = $weight";
        try (Session s = neo4j.session()) {
            s.run(query, Values.parameters(
                    "from", (long) fromId,
                    "to", (long) toId,
                    "weight", weight
            ));
        } catch (Exception e) {
            log.warn("Neo4j AddMemoryEdge 失败 ({} -> {}): {}", fromId, toId, e.getMessage());
        }
    }

    public List<Integer> expandMemoryNeighbors(List<Integer> seedIds, int hops) {
        if (!available() || seedIds == null || seedIds.isEmpty()) return List.of();
        List<Long> longSeeds = new ArrayList<>();
        for (int id : seedIds) longSeeds.add((long) id);
        int h = hops > 0 ? hops : 1;
        String query = "MATCH (m:Memory) WHERE m.mem_id IN $ids " +
                "MATCH (m)-[:FOLLOWS|SIMILAR_TO|CAUSES|BELONGS_TO*1.." + h + "]-(n:Memory) " +
                "WHERE NOT n.mem_id IN $ids RETURN DISTINCT n.mem_id AS id";
        List<Integer> result = new ArrayList<>();
        try (Session s = neo4j.session()) {
            Result rs = s.run(query, Values.parameters("ids", longSeeds));
            while (rs.hasNext()) {
                result.add((int) rs.next().get("id").asLong(-1));
            }
        } catch (Exception e) {
            log.warn("Neo4j expandMemoryNeighbors 失败: {}", e.getMessage());
        }
        return result;
    }

    public void deleteMemoryNode(int memId) {
        if (!available()) return;
        try (Session s = neo4j.session()) {
            s.run("MATCH (m:Memory {mem_id: $id}) DETACH DELETE m",
                    Values.parameters("id", (long) memId));
        } catch (Exception e) {
            log.warn("Neo4j DeleteMemoryNode 失败 (id={}): {}", memId, e.getMessage());
        }
    }

    /**
     * 在待删除列表中找出图中入度较高（受保护）的节点
     */
    public List<Integer> getHighCentralityMemoryIds(List<Integer> candidates, int threshold) {
        if (!available() || candidates == null || candidates.isEmpty()) return List.of();
        List<Long> longIds = new ArrayList<>();
        for (int id : candidates) longIds.add((long) id);
        String query = "MATCH (m:Memory) WHERE m.mem_id IN $ids " +
                "WITH m, size([(m)<-[]-() | 1]) AS indegree " +
                "WHERE indegree >= $threshold RETURN m.mem_id AS id";
        List<Integer> result = new ArrayList<>();
        try (Session s = neo4j.session()) {
            Result rs = s.run(query, Values.parameters(
                    "ids", longIds,
                    "threshold", (long) threshold
            ));
            while (rs.hasNext()) {
                result.add((int) rs.next().get("id").asLong(-1));
            }
        } catch (Exception e) {
            log.warn("Neo4j getHighCentralityMemoryIds 失败: {}", e.getMessage());
        }
        return result;
    }

    private static boolean isValidMemoryEdge(String type) {
        return "FOLLOWS".equals(type) || "SIMILAR_TO".equals(type)
                || "CAUSES".equals(type) || "BELONGS_TO".equals(type);
    }
}
