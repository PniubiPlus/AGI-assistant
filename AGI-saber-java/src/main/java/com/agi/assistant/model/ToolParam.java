package com.agi.assistant.model;

public class ToolParam {
    private String name;
    private String type;
    private String description;
    private boolean required;

    public ToolParam() {}
    public ToolParam(String name, String type, String description, boolean required) {
        this.name = name; this.type = type; this.description = description; this.required = required;
    }

    // getters/setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
}
