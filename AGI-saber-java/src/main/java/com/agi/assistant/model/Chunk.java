package com.agi.assistant.model;

public class Chunk {
    private int id;
    private String content;

    public Chunk() {}
    public Chunk(int id, String content) { this.id = id; this.content = content; }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
