package com.agi.assistant.service.graph;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 知识图谱中的一个节点（对应 Go graph.Entity）
 */
public class Entity {
    private String name;
    private EntityType type;
    @JsonProperty("doc_hash")
    private String docHash;
    @JsonProperty("chunk_id")
    private int chunkId;

    public Entity() {}

    public Entity(String name, EntityType type) {
        this.name = name;
        this.type = type;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public EntityType getType() { return type; }
    public void setType(EntityType type) { this.type = type; }
    public String getDocHash() { return docHash; }
    public void setDocHash(String docHash) { this.docHash = docHash; }
    public int getChunkId() { return chunkId; }
    public void setChunkId(int chunkId) { this.chunkId = chunkId; }
}
