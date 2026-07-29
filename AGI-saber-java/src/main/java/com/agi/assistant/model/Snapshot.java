package com.agi.assistant.model;

public class Snapshot {
    private TaskState state;
    private String timestamp;

    public Snapshot() {}
    public Snapshot(TaskState state, String timestamp) { this.state = state; this.timestamp = timestamp; }

    public TaskState getState() { return state; }
    public void setState(TaskState state) { this.state = state; }
    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
}
