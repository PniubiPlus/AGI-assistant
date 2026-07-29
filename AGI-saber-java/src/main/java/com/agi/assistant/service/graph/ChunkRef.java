package com.agi.assistant.service.graph;

/**
 * KGStore 摄入接口接收的轻量 chunk 引用，避免引入 RAG 包形成循环依赖
 * （对应 Go graph.ChunkRef）
 */
public class ChunkRef {
    private final int id;
    private final String content;

    public ChunkRef(int id, String content) {
        this.id = id;
        this.content = content;
    }

    public int getId() { return id; }
    public String getContent() { return content; }
}
