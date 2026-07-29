package com.agi.assistant.model;

import java.util.Map;

public class ReActStep {
    public static final String THOUGHT = "Thought";
    public static final String ACTION = "Action";
    public static final String OBSERVATION = "Observation";
    public static final String FINAL_ANSWER = "Final Answer";

    private String type;
    private String content;
    private String tool;
    private Map<String, String> params;

    public ReActStep() {}
    public ReActStep(String type, String content) { this.type = type; this.content = content; }
    public ReActStep(String type, String content, String tool, Map<String, String> params) {
        this.type = type; this.content = content; this.tool = tool; this.params = params;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getTool() { return tool; }
    public void setTool(String tool) { this.tool = tool; }
    public Map<String, String> getParams() { return params; }
    public void setParams(Map<String, String> params) { this.params = params; }
}
