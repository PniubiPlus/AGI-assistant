package com.agi.assistant.service.rag;

import com.agi.assistant.config.AppConfig;
import com.agi.assistant.infrastructure.InfrastructureService;
import com.agi.assistant.model.Chunk;
import com.agi.assistant.service.graph.ChunkRef;
import com.agi.assistant.service.graph.KGStore;
import com.agi.assistant.service.memory.LongTermMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * RAG Engine - integrates text splitting, hybrid retrieval, and answer generation.
 * 对应 Go internal/rag/rag.go
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final AppConfig cfg;
    private final HybridStore store;
    private final TextSplitter splitter;
    private final InfrastructureService infra;
    private KGStore kg;

    private boolean loaded = false;
    private BiFunction<String, String, String> generateFn;
    private Function<String, List<Double>> embedFn;

    public RagService(AppConfig cfg, HybridStore store, TextSplitter splitter, InfrastructureService infra) {
        this.cfg = cfg;
        this.store = store;
        this.splitter = splitter;
        this.infra = infra;
        this.splitter.setChunkSize(cfg.getRag().getChunkSize());
        this.splitter.setOverlap(cfg.getRag().getChunkOverlap());
    }

    public void setGenerateFn(BiFunction<String, String, String> fn) { this.generateFn = fn; }
    public void setEmbedFn(Function<String, List<Double>> fn) {
        this.embedFn = fn;
        this.store.setEmbedFn(fn);
    }
    public void setKGStore(KGStore kg) {
        this.kg = kg;
        this.store.setKGStore(kg);
    }

    public boolean isLoaded() { return loaded; }
    public String getMode() { return store.getMode(); }

    public Map.Entry<Integer, String> ingest(String doc) {
        List<Chunk> chunks = splitter.split(doc);
        String docHash = store.index(chunks, doc);
        loaded = true;
        infra.publishEvent("rag.ingest",
                String.format("{\"chunk_count\":%d,\"mode\":\"%s\",\"doc_hash\":\"%s\"}",
                        chunks.size(), store.getMode(), docHash));
        // 异步建图
        if (kg != null && kg.available()) {
            List<ChunkRef> refs = new ArrayList<>();
            for (Chunk c : chunks) refs.add(new ChunkRef(c.getId(), c.getContent()));
            new Thread(() -> kg.indexDocument(docHash, refs), "kg-index").start();
        }
        return Map.entry(chunks.size(), docHash);
    }

    public void delete(String docHash) {
        store.delete(docHash);
        if (kg != null && kg.available()) kg.deleteDocument(docHash);
        // 重新检测是否还有 chunks
        loaded = !infra.loadAllRAGChunks().isEmpty();
    }

    public QueryResult query(String question) {
        if (!loaded) {
            return new QueryResult("知识库为空，请先上传文档。", Collections.emptyList());
        }

        // 优先用 HybridStore（hybrid / semantic / keyword）
        List<HybridStore.SearchResult> hybridResults = store.search(question, cfg.getRag().getTopK());
        List<ScoredChunk> results = new ArrayList<>();
        if (hybridResults != null) {
            for (HybridStore.SearchResult r : hybridResults) {
                results.add(new ScoredChunk(r.chunk, r.score));
            }
        }

        // unavailable 模式：兜底 TF 搜索（基于 PG 中已存的 chunks）
        if (results.isEmpty() && "unavailable".equals(store.getMode())) {
            results = tfSearch(question, cfg.getRag().getTopK());
        }

        if (results.isEmpty()) {
            return new QueryResult("知识库中未找到相关内容。", Collections.emptyList());
        }

        String context = results.stream()
                .map(r -> r.chunk.getContent())
                .collect(Collectors.joining("\n\n"));

        String answer;
        if (generateFn != null) {
            String systemPrompt = "你是一个基于知识库回答问题的助手。请仅根据提供的上下文内容回答问题，不要编造信息。如果上下文不足以回答，请说明。";
            String userMsg = String.format("上下文：\n%s\n\n问题：%s", context, question);
            answer = generateFn.apply(systemPrompt, userMsg);
        } else {
            answer = "【知识库检索结果】\n" + context;
        }
        return new QueryResult(answer, results);
    }

    public List<Chunk> getChunks() {
        List<InfrastructureService.ChunkRow> rows = infra.loadAllRAGChunks();
        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            chunks.add(new Chunk(i, rows.get(i).content));
        }
        return chunks;
    }

    public void restoreChunks(List<Chunk> chunks) {
        loaded = !chunks.isEmpty();
        store.restoreChunks(chunks);
    }

    // ============ TF 兜底搜索 ============

    private List<ScoredChunk> tfSearch(String query, int topK) {
        List<InfrastructureService.ChunkRow> rows = infra.loadAllRAGChunks();
        if (rows.isEmpty()) return Collections.emptyList();
        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) chunks.add(new Chunk(i, rows.get(i).content));

        Set<String> allTokens = new LinkedHashSet<>();
        List<String> queryTokens = LongTermMemory.tokenize(query);
        allTokens.addAll(queryTokens);
        for (Chunk c : chunks) allTokens.addAll(LongTermMemory.tokenize(c.getContent()));
        List<String> vocabList = new ArrayList<>(allTokens);
        Map<String, Integer> vocabIdx = new HashMap<>();
        for (int i = 0; i < vocabList.size(); i++) vocabIdx.put(vocabList.get(i), i);

        double[] qVec = new double[vocabList.size()];
        for (String t : queryTokens) {
            Integer idx = vocabIdx.get(t);
            if (idx != null) qVec[idx]++;
        }

        List<ScoredChunk> scored = new ArrayList<>();
        for (Chunk c : chunks) {
            double[] cVec = new double[vocabList.size()];
            for (String t : LongTermMemory.tokenize(c.getContent())) {
                Integer idx = vocabIdx.get(t);
                if (idx != null) cVec[idx]++;
            }
            double sim = cosine(qVec, cVec);
            if (sim > 0) scored.add(new ScoredChunk(c, sim));
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        return scored.subList(0, Math.min(topK, scored.size()));
    }

    private double cosine(double[] a, double[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    // ============ Result types ============

    public static class ScoredChunk {
        public Chunk chunk;
        public double score;
        public ScoredChunk(Chunk chunk, double score) { this.chunk = chunk; this.score = score; }
    }

    public static class QueryResult {
        public String answer;
        public List<ScoredChunk> results;
        public QueryResult(String answer, List<ScoredChunk> results) {
            this.answer = answer; this.results = results;
        }
    }
}
