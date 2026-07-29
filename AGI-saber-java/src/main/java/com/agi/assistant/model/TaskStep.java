package com.agi.assistant.model;

import java.util.Map;

public class TaskStep {
    public static final String PENDING = "pending";
    public static final String RUNNING = "running";
    public static final String DONE = "done";
    public static final String FAILED = "failed";
    public static final String INTERRUPTED = "interrupted";

    private int id;
    private String name;
    private String toolName;
    private Map<String, String> params;
    private String status = PENDING;
    private String result;
    private String error;
    private int retryCount;

    public TaskStep() {}
    public TaskStep(int id, String name, String toolName, Map<String, String> params) {
        this.id = id; this.name = name; this.toolName = toolName; this.params = params;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public Map<String, String> getParams() { return params; }
    public void setParams(Map<String, String> params) { this.params = params; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
}
