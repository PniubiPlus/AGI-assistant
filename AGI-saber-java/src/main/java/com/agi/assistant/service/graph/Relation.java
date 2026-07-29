package com.agi.assistant.service.graph;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 知识图谱中的一条有向关系边（对应 Go graph.Relation）
 */
public class Relation {
    @JsonProperty("from")
    private String fromName;
    @JsonProperty("to")
    private String toName;
    /** RELATES_TO / PART_OF / CAUSES / DESCRIBES / MENTIONS / WORKS_FOR / LOCATED_IN */
    @JsonProperty("rel_type")
    private String relType;
    private double weight;
    @JsonProperty("doc_hash")
    private String docHash;
    @JsonProperty("chunk_id")
    private int chunkId;

    public Relation() {}

    public Relation(String fromName, String toName, String relType) {
        this.fromName = fromName;
        this.toName = toName;
        this.relType = relType;
    }

    public String getFromName() { return fromName; }
    public void setFromName(String fromName) { this.fromName = fromName; }
    public String getToName() { return toName; }
    public void setToName(String toName) { this.toName = toName; }
    public String getRelType() { return relType; }
    public void setRelType(String relType) { this.relType = relType; }
    public double getWeight() { return weight; }
    public void setWeight(double weight) { this.weight = weight; }
    public String getDocHash() { return docHash; }
    public void setDocHash(String docHash) { this.docHash = docHash; }
    public int getChunkId() { return chunkId; }
    public void setChunkId(int chunkId) { this.chunkId = chunkId; }
}
