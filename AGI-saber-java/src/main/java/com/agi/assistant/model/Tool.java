package com.agi.assistant.model;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class Tool {
    private String name;
    private String description;
    private List<ToolParam> parameters;
    private boolean mcp;
    private transient Function<Map<String, Object>, String> execute;

    public Tool() {}
    public Tool(String name, String description, List<ToolParam> parameters, Function<Map<String, Object>, String> execute) {
        this.name = name; this.description = description; this.parameters = parameters; this.execute = execute;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<ToolParam> getParameters() { return parameters; }
    public void setParameters(List<ToolParam> parameters) { this.parameters = parameters; }
    public boolean isMcp() { return mcp; }
    public void setMcp(boolean mcp) { this.mcp = mcp; }
    public Function<Map<String, Object>, String> getExecute() { return execute; }
    public void setExecute(Function<Map<String, Object>, String> execute) { this.execute = execute; }
}
