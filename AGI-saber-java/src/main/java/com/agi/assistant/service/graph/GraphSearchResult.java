package com.agi.assistant.service.graph;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次图检索的单条结果（对应 Go graph.GraphSearchResult）
 */
public class GraphSearchResult {
    @JsonProperty("chunk_id")
    private int chunkId;
    private double score;
    private List<String> entities = new ArrayList<>();
    @JsonProperty("hop_path")
    private List<String> hopPath = new ArrayList<>();

    public GraphSearchResult() {}

    public GraphSearchResult(int chunkId, double score, List<String> entities, List<String> hopPath) {
        this.chunkId = chunkId;
        this.score = score;
        this.entities = entities != null ? entities : new ArrayList<>();
        this.hopPath = hopPath != null ? hopPath : new ArrayList<>();
    }

    public int getChunkId() { return chunkId; }
    public void setChunkId(int chunkId) { this.chunkId = chunkId; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
    public List<String> getEntities() { return entities; }
    public void setEntities(List<String> entities) { this.entities = entities; }
    public List<String> getHopPath() { return hopPath; }
    public void setHopPath(List<String> hopPath) { this.hopPath = hopPath; }
}
