package com.agi.assistant.controller;

import com.agi.assistant.config.AppConfig;
import com.agi.assistant.dto.ChatRequest;
import com.agi.assistant.dto.ChatResponse;
import com.agi.assistant.infrastructure.InfrastructureService;
import com.agi.assistant.model.*;
import com.agi.assistant.service.agent.UnifiedAgentService;
import com.agi.assistant.service.rag.RagService;
import com.agi.assistant.service.tools.ToolService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * REST API Controller - corresponds to Go internal/handler/handler.go
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class AgentController {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final UnifiedAgentService agent;
    private final InfrastructureService infra;
    private final AppConfig cfg;
    private final ToolService toolService;

    public AgentController(UnifiedAgentService agent, InfrastructureService infra,
                           AppConfig cfg, ToolService toolService) {
        this.agent = agent;
        this.infra = infra;
        this.cfg = cfg;
        this.toolService = toolService;
    }

    /**
     * POST /api/chat - Unified conversation entry point (sync)
     */
    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest req) {
        return agent.processWithOptions(req.getMessage(), req);
    }

    /**
     * POST /api/chat/stream - SSE streaming conversation
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatRequest req) {
        SseEmitter emitter = new SseEmitter(60000L);
        executor.submit(() -> {
            try {
                emitter.send(SseEmitter.event().name("start")
                        .data(Map.of("message", req.getMessage())));
                ChatResponse resp = agent.processWithOptions(req.getMessage(), req);
                emitter.send(SseEmitter.event().name("done").data(resp));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    /**
     * POST /api/chat/cancel - Cancel current task
     */
    @PostMapping("/chat/cancel")
    public Map<String, Object> chatCancel() {
        agent.cancel();
        return Map.of("ok", true, "message", "已发送取消信号");
    }

    /**
     * POST /api/upload - Upload document to RAG
     */
    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestBody Map<String, String> req) {
        String content = req.get("content");
        if (content == null || content.isEmpty()) {
            return Map.of("error", "content is required");
        }
        Map.Entry<Integer, String> result = agent.getRagService().ingest(content, req.get("document_name"));
        List<Chunk> chunks = agent.getRagService().getChunks();
        return Map.of(
                "chunk_count", result.getKey(),
                "doc_hash", result.getValue(),
                "chunks", chunks
        );
    }

    /**
     * POST /api/docs/delete - Delete RAG document by hash
     */
    @PostMapping("/docs/delete")
    public Map<String, Object> docsDelete(@RequestBody Map<String, String> req) {
        String docHash = req.get("doc_hash");
        if (docHash == null || docHash.isEmpty()) {
            return Map.of("error", "doc_hash is required");
        }
        RagService.DeletedDocument deleted = agent.getRagService().delete(docHash);
        return Map.of("ok", true, "doc_hash", docHash,
                "document_name", deleted.documentName, "chunk_count", deleted.chunkCount);
    }

    /**
     * GET /api/docs/active - List documents currently in the knowledge base
     */
    @GetMapping("/docs/active")
    public List<Map<String, Object>> activeDocs() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (InfrastructureService.DeletedDocumentRow row : infra.loadActiveDocuments()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("doc_hash", row.docHash);
            item.put("document_name", row.documentName);
            item.put("chunk_count", row.chunkCount);
            result.add(item);
        }
        return result;
    }

    /**
     * GET /api/docs/deleted - List deleted document history
     */
    @GetMapping("/docs/deleted")
    public List<Map<String, Object>> deletedDocs(@RequestParam(defaultValue = "100") int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (InfrastructureService.DeletedDocumentHistoryRow row : infra.loadDeletedDocuments(limit)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.id);
            item.put("doc_hash", row.docHash);
            item.put("document_name", row.documentName);
            item.put("chunk_count", row.chunkCount);
            item.put("deleted_at", row.deletedAt);
            result.add(item);
        }
        return result;
    }

    /**
     * GET /api/memory - View three-layer memory state
     */
    @GetMapping("/memory")
    public Map<String, Object> memory() {
        return Map.of(
                "short_term", agent.getShortTermMemory().getMessages(),
                "long_term", agent.getLongTermMemory().getItems(),
                "preference", agent.getPreferences().getData()
        );
    }

    /**
     * GET /api/tools - List available tools
     */
    @GetMapping("/tools")
    public List<Map<String, Object>> toolsList() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Tool t : agent.getTools().values()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", t.getName());
            info.put("description", t.getDescription());
            if (t.isMcp()) info.put("is_mcp", true);
            if (t.getParameters() != null && !t.getParameters().isEmpty()) {
                info.put("params", t.getParameters());
            }
            list.add(info);
        }
        return list;
    }

    /**
     * POST /api/tools/mcp - Register external MCP tool
     */
    @PostMapping("/tools/mcp")
    public Map<String, Object> registerMCPTool(@RequestBody Map<String, Object> req) {
        String name = (String) req.get("name");
        String description = (String) req.get("description");
        String endpoint = (String) req.get("endpoint");

        if (name == null || name.isEmpty() || endpoint == null || endpoint.isEmpty()) {
            return Map.of("error", "name and endpoint are required");
        }

        List<ToolParam> params = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> paramsList = (List<Map<String, Object>>) req.get("params");
        if (paramsList != null) {
            for (Map<String, Object> p : paramsList) {
                params.add(new ToolParam(
                        (String) p.get("name"),
                        (String) p.get("type"),
                        (String) p.get("description"),
                        Boolean.TRUE.equals(p.get("required"))
                ));
            }
        }

        Tool tool = toolService.createMCPTool(name, description != null ? description : "", endpoint, params);
        agent.registerTool(tool);
        return Map.of("ok", true, "name", name);
    }

    /**
     * GET /api/snapshots - List task execution snapshots
     */
    @GetMapping("/snapshots")
    public List<Map<String, Object>> snapshots() {
        List<Snapshot> snaps = agent.getSnapshots();
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < snaps.size(); i++) {
            Snapshot snap = snaps.get(i);
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("index", i);
            info.put("timestamp", snap.getTimestamp());
            info.put("steps", snap.getState().getSteps() != null ? snap.getState().getSteps().size() : 0);
            result.add(info);
        }
        return result;
    }

    /**
     * GET /api/status - System status and config summary
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        RagService ragService = agent.getRagService();
        List<Chunk> chunks = ragService.getChunks();
        List<Map<String, Object>> chunkPreviews = new ArrayList<>();
        for (Chunk c : chunks) {
            String preview = c.getContent();
            if (preview.length() > 60) {
                preview = preview.substring(0, 60) + "...";
            }
            chunkPreviews.add(Map.of("id", c.getId(), "content", preview));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rag_loaded", ragService.isLoaded());
        result.put("rag_mode", ragService.getMode());
        result.put("rag_chunks", chunkPreviews);
        result.put("short_term_count", agent.getShortTermMemory().size());
        result.put("long_term_count", agent.getLongTermMemory().size());
        result.put("preferences", agent.getPreferences().getData());
        result.put("tools_count", agent.getTools().size());
        result.put("llm_model", cfg.getLlm().getModel());
        result.put("embedding_model", cfg.getEmbedding().getModel());
        result.put("is_mock", !cfg.isRealLLM());
        result.put("infrastructure", infra.getStatus());
        return result;
    }
}
