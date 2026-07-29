package com.agi.assistant.model;

import java.time.LocalDateTime;
import java.util.List;

public class MemoryItem {
    private int id;
    private String content;
    private double importance;
    private List<Double> embedding;
    private double score;
    private LocalDateTime createdAt;
    private LocalDateTime lastAccessed;

    public MemoryItem() {}
    public MemoryItem(int id, String content, double importance, List<Double> embedding) {
        this.id = id; this.content = content; this.importance = importance; this.embedding = embedding;
        this.createdAt = LocalDateTime.now(); this.lastAccessed = LocalDateTime.now();
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public double getImportance() { return importance; }
    public void setImportance(double importance) { this.importance = importance; }
    public List<Double> getEmbedding() { return embedding; }
    public void setEmbedding(List<Double> embedding) { this.embedding = embedding; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getLastAccessed() { return lastAccessed; }
    public void setLastAccessed(LocalDateTime lastAccessed) { this.lastAccessed = lastAccessed; }
}
