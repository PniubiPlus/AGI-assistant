package com.agi.assistant.service.rag;

import com.agi.assistant.config.AppConfig;
import com.agi.assistant.infrastructure.InfrastructureService;
import com.agi.assistant.model.Chunk;
import com.agi.assistant.service.graph.GraphSearchResult;
import com.agi.assistant.service.graph.KGStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * 混合检索存储：语义向量（Milvus）+ 关键词（ES BM25）+ 知识图谱（Neo4j）+ RRF 融合。
 * 对应 Go internal/rag/hybrid.go
 */
@Component
public class HybridStore {

    private static final Logger log = LoggerFactory.getLogger(HybridStore.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final AppConfig cfg;
    private final InfrastructureService infra;
    private Function<String, List<Double>> embedFn;
    private KGStore kg;
    private String mode = "unavailable";

    public HybridStore(AppConfig cfg, InfrastructureService infra) {
        this.cfg = cfg;
        this.infra = infra;
        recomputeMode();
    }

    /** 由 RagService 在基础设施初始化完成后调用一次以重算模式 */
    public void recomputeMode() {
        boolean milvusOK = "connected".equals(infra.getMilvusStatus());
        boolean esOK = "connected".equals(infra.getEsStatus());
        if (milvusOK && esOK) mode = "hybrid";
        else if (milvusOK) mode = "semantic";
        else if (esOK) mode = "keyword";
        else mode = "unavailable";
    }

    public void setEmbedFn(Function<String, List<Double>> fn) { this.embedFn = fn; }
    public void setKGStore(KGStore kg) { this.kg = kg; }
    public String getMode() { return mode; }

    /** Index：写入 PG + Milvus + ES，返回 docHash */
    public String index(List<Chunk> chunks, String docContent) {
        String docHash = sha256(docContent).substring(0, 16);

        List<Long> pgIds = new ArrayList<>();
        List<String> contents = new ArrayList<>();
        List<List<Float>> embeddings = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            List<Double> emb = null;
            if (embedFn != null) {
                emb = embedFn.apply(c.getContent());
            }
            String embJson = "null";
            if (emb != null && !emb.isEmpty()) {
                try { embJson = mapper.writeValueAsString(emb); } catch (Exception ignored) {}
            }
            long pgId = infra.saveRAGChunk(docHash, i, c.getContent(), embJson);
            if (pgId < 0) {
                log.warn("RAG chunk 写入 PG 失败 (idx={})", i);
                continue;
            }
            // ES
            if ("connected".equals(infra.getEsStatus())) {
                infra.indexRAGChunkInES(pgId, c.getContent(), docHash, i);
            }
            // Milvus 收集批量插入
            if ("connected".equals(infra.getMilvusStatus()) && emb != null && !emb.isEmpty()) {
                pgIds.add(pgId);
                contents.add(c.getContent());
                List<Float> emb32 = new ArrayList<>(emb.size());
                for (Double v : emb) emb32.add(v.floatValue());
                embeddings.add(emb32);
            }
        }
        if (!pgIds.isEmpty()) {
            infra.insertRAGChunks(pgIds, contents, embeddings);
        }
        return docHash;
    }

    public List<Long> delete(String docHash) {
        List<Long> pgIds = infra.deleteRAGChunksByDocHash(docHash);
        if (pgIds.isEmpty()) return pgIds;
        if ("connected".equals(infra.getEsStatus())) infra.deleteRAGChunksFromES(pgIds);
        if ("connected".equals(infra.getMilvusStatus())) infra.deleteRAGChunksFromMilvus(pgIds);
        return pgIds;
    }

    public void restoreChunks(List<Chunk> chunks) {
        // chunks 已在 PG 中，无需额外操作
    }

    public List<SearchResult> search(String query, int topK) {
        recomputeMode();
        return switch (mode) {
            case "hybrid" -> searchHybrid(query, topK);
            case "semantic" -> searchSemantic(query, topK);
            case "keyword" -> searchKeyword(query, topK);
            default -> Collections.emptyList();
        };
    }

    // ============ Hybrid: RRF 融合 ============

    private List<SearchResult> searchHybrid(String query, int topK) {
        if (embedFn == null) return searchKeyword(query, topK);
        List<Double> emb = embedFn.apply(query);
        if (emb == null || emb.isEmpty()) {
            log.warn("查询向量化失败，降级到关键词检索");
            return searchKeyword(query, topK);
        }
        List<Float> queryVec = new ArrayList<>(emb.size());
        for (Double v : emb) queryVec.add(v.floatValue());

        int fetchK = Math.max(10, topK * 2);
        List<InfrastructureService.MilvusHit> milvusHits = infra.milvusSearchWithScores(queryVec, fetchK);
        List<InfrastructureService.ESHit> esHits = infra.searchRAGChunks(query, fetchK);

        if (milvusHits.isEmpty() && esHits.isEmpty()) {
            return Collections.emptyList();
        }

        int k = cfg.getRag().getRrfConstantK() > 0 ? cfg.getRag().getRrfConstantK() : 60;
        Map<Long, Double> rrf = new HashMap<>();
        for (int i = 0; i < milvusHits.size(); i++) {
            long id = milvusHits.get(i).id;
            rrf.merge(id, 1.0 / (k + i + 1), Double::sum);
        }
        for (int i = 0; i < esHits.size(); i++) {
            long id = esHits.get(i).pgId;
            rrf.merge(id, 1.0 / (k + i + 1), Double::sum);
        }

        // 知识图谱第三路融合
        if (kg != null && kg.available()) {
            List<GraphSearchResult> kgHits = kg.search(query, fetchK);
            for (int i = 0; i < kgHits.size(); i++) {
                long id = kgHits.get(i).getChunkId();
                double extra = kgHits.get(i).getScore() + 1.0 / (k + i + 1);
                rrf.merge(id, extra, Double::sum);
            }
        }

        List<Map.Entry<Long, Double>> sorted = new ArrayList<>(rrf.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        if (sorted.size() > topK) sorted = sorted.subList(0, topK);

        List<Long> ids = new ArrayList<>();
        for (Map.Entry<Long, Double> e : sorted) ids.add(e.getKey());
        Map<Long, String> contentMap = loadContentMap(ids);

        List<SearchResult> results = new ArrayList<>();
        for (Map.Entry<Long, Double> e : sorted) {
            String content = contentMap.get(e.getKey());
            if (content == null) continue;
            results.add(new SearchResult(new Chunk(0, content), e.getValue(), "hybrid"));
        }
        return results;
    }

    private List<SearchResult> searchSemantic(String query, int topK) {
        if (embedFn == null) return Collections.emptyList();
        List<Double> emb = embedFn.apply(query);
        if (emb == null || emb.isEmpty()) return Collections.emptyList();
        List<Float> queryVec = new ArrayList<>(emb.size());
        for (Double v : emb) queryVec.add(v.floatValue());

        List<InfrastructureService.MilvusHit> hits = infra.milvusSearchWithScores(queryVec, topK);
        List<Long> ids = new ArrayList<>();
        for (InfrastructureService.MilvusHit h : hits) ids.add(h.id);
        Map<Long, String> contentMap = loadContentMap(ids);

        List<SearchResult> results = new ArrayList<>();
        for (InfrastructureService.MilvusHit h : hits) {
            String content = contentMap.get(h.id);
            if (content == null) continue;
            results.add(new SearchResult(new Chunk(0, content), h.distance, "semantic"));
        }
        return results;
    }

    private List<SearchResult> searchKeyword(String query, int topK) {
        List<InfrastructureService.ESHit> hits = infra.searchRAGChunks(query, topK);
        List<Long> ids = new ArrayList<>();
        for (InfrastructureService.ESHit h : hits) ids.add(h.pgId);
        Map<Long, String> contentMap = loadContentMap(ids);

        List<SearchResult> results = new ArrayList<>();
        for (InfrastructureService.ESHit h : hits) {
            String content = contentMap.get(h.pgId);
            if (content == null) continue;
            results.add(new SearchResult(new Chunk(0, content), h.score, "keyword"));
        }
        return results;
    }

    private Map<Long, String> loadContentMap(List<Long> ids) {
        Map<Long, String> map = new LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) return map;
        List<InfrastructureService.ChunkRow> rows = infra.loadRAGChunksByIDs(ids);
        for (InfrastructureService.ChunkRow r : rows) map.put(r.id, r.content);
        return map;
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
    }

    public static class SearchResult {
        public Chunk chunk;
        public double score;
        public String source;

        public SearchResult(Chunk chunk, double score, String source) {
            this.chunk = chunk; this.score = score; this.source = source;
        }
    }
}
