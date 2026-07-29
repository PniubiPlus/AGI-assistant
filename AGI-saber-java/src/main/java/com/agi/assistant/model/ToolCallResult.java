package com.agi.assistant.model;

import java.util.Map;

public class ToolCallResult {
    private String toolName;
    private Map<String, Object> params;
    private String toolResult;

    public ToolCallResult() {}
    public ToolCallResult(String toolName, Map<String, Object> params) {
        this.toolName = toolName; this.params = params;
    }

    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public Map<String, Object> getParams() { return params; }
    public void setParams(Map<String, Object> params) { this.params = params; }
    public String getToolResult() { return toolResult; }
    public void setToolResult(String toolResult) { this.toolResult = toolResult; }
}
