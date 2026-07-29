package com.agi.assistant.service.memory;

import com.agi.assistant.model.MemoryItem;
import com.agi.assistant.service.graph.KGStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * GraphMemory 在 LongTerm 之上叠加 Neo4j 图层。
 * 对应 Go internal/memory/graph_memory.go
 *
 * 节点类型：(:Memory {mem_id, content, importance})
 * 边类型：FOLLOWS / SIMILAR_TO / CAUSES / BELONGS_TO
 */
public class GraphMemory {

    private static final Logger log = LoggerFactory.getLogger(GraphMemory.class);

    private final LongTermMemory ltm;
    private final KGStore kg;
    private final double simThreshold;
    private volatile int prevId = -1;

    public GraphMemory(LongTermMemory ltm, KGStore kg, double simThreshold) {
        this.ltm = ltm;
        this.kg = kg;
        this.simThreshold = simThreshold > 0 ? simThreshold : 0.7;
    }

    public LongTermMemory ltm() { return ltm; }

    /** Store 写入 LTM 并在图中建立节点和边。返回是否新增（false=去重跳过）+ 实际 ID。 */
    public StoreResult store(String content, double importance, List<Double> embedding) {
        boolean added = ltm.store(content, importance, embedding);
        if (!added) {
            return new StoreResult(false, findMostSimilarId(embedding));
        }
        // 取刚加入条目
        List<MemoryItem> items = ltm.getItems();
        if (items.isEmpty()) return new StoreResult(true, -1);
        MemoryItem newItem = items.get(items.size() - 1);
        int newId = newItem.getId();

        if (kg != null && kg.available()) {
            new Thread(() -> {
                kg.upsertMemoryNode(newId, content, importance);
                if (prevId >= 0) {
                    kg.addMemoryEdge(prevId, newId, "FOLLOWS", 1.0);
                }
                linkSimilarEdges(newItem, newId);
            }, "graph-memory-store").start();
        }

        prevId = newId;
        return new StoreResult(true, newId);
    }

    private void linkSimilarEdges(MemoryItem newItem, int newId) {
        List<MemoryItem> items = ltm.getItems();
        int start = Math.max(0, items.size() - 51);
        for (int i = start; i < items.size() - 1; i++) {
            MemoryItem old = items.get(i);
            if (old.getId() == newId) continue;
            if (old.getEmbedding() == null || old.getEmbedding().isEmpty()
                    || newItem.getEmbedding() == null || newItem.getEmbedding().isEmpty()) continue;
            if (old.getEmbedding().size() != newItem.getEmbedding().size()) continue;
            double sim = LongTermMemory.cosine(old.getEmbedding(), newItem.getEmbedding());
            if (sim >= simThreshold) {
                kg.addMemoryEdge(old.getId(), newId, "SIMILAR_TO", sim);
            }
        }
    }

    private int findMostSimilarId(List<Double> embedding) {
        if (embedding == null || embedding.isEmpty()) return -1;
        int bestId = -1;
        double bestSim = 0;
        for (MemoryItem it : ltm.getItems()) {
            if (it.getEmbedding() == null || it.getEmbedding().size() != embedding.size()) continue;
            double s = LongTermMemory.cosine(embedding, it.getEmbedding());
            if (s > bestSim) {
                bestSim = s;
                bestId = it.getId();
            }
        }
        return bestId;
    }

    /** Recall：先做向量召回，再用图扩展邻居 */
    public List<MemoryItem> recall(String query, int topK, List<Double> queryEmbedding) {
        List<MemoryItem> seed = ltm.recall(query, topK, queryEmbedding);
        if (kg == null || !kg.available() || seed.isEmpty()) {
            return seed;
        }
        List<Integer> seedIds = new ArrayList<>();
        for (MemoryItem it : seed) seedIds.add(it.getId());
        List<Integer> expandedIds = kg.expandMemoryNeighbors(seedIds, 1);
        if (expandedIds.isEmpty()) return seed;

        Set<Integer> idSet = new HashSet<>(seedIds);
        List<MemoryItem> expanded = new ArrayList<>();
        for (int id : expandedIds) {
            if (idSet.contains(id)) continue;
            for (MemoryItem it : ltm.getItems()) {
                if (it.getId() == id) {
                    it.setScore(0.45);
                    expanded.add(it);
                    idSet.add(id);
                    break;
                }
            }
        }

        List<MemoryItem> all = new ArrayList<>(seed);
        all.addAll(expanded);
        all.sort(Comparator.comparingDouble(MemoryItem::getScore).reversed());
        if (all.size() > topK) all = all.subList(0, topK);
        return all;
    }

    /** GraphAwareConsolidate：在 LTM 合并基础上保护图中心度高的节点 */
    public LongTermMemory.ConsolidationResult graphAwareConsolidate() {
        LongTermMemory.ConsolidationResult result = ltm.consolidate();
        if (kg == null || !kg.available()) return result;

        List<Integer> protectedIds = kg.getHighCentralityMemoryIds(result.deleteFromDB, 3);
        if (!protectedIds.isEmpty()) {
            Set<Integer> protSet = new HashSet<>(protectedIds);
            List<Integer> filtered = new ArrayList<>();
            for (int id : result.deleteFromDB) {
                if (!protSet.contains(id)) filtered.add(id);
            }
            log.info("图中心度保护：{} 条记忆免于删除（入度≥3）",
                    result.deleteFromDB.size() - filtered.size());
            result.deleteFromDB = filtered;
        }

        List<Integer> toDelete = new ArrayList<>(result.deleteFromDB);
        new Thread(() -> {
            for (int id : toDelete) kg.deleteMemoryNode(id);
        }, "graph-mem-delete").start();
        return result;
    }

    public void syncPrevId() {
        List<MemoryItem> items = ltm.getItems();
        if (!items.isEmpty()) prevId = items.get(items.size() - 1).getId();
    }

    public void syncLastItemPGID(int pgId) {
        ltm.syncLastItemPGID(pgId);
        List<MemoryItem> items = ltm.getItems();
        if (!items.isEmpty()) {
            MemoryItem last = items.get(items.size() - 1);
            prevId = last.getId();
            if (kg != null && kg.available()) {
                new Thread(() -> {
                    try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                    kg.upsertMemoryNode(last.getId(), last.getContent(), last.getImportance());
                }, "graph-mem-sync").start();
            }
        }
    }

    public boolean needConsolidation() { return ltm.needConsolidation(); }

    public int size() { return ltm.size(); }

    public record StoreResult(boolean added, int itemId) {}
}
