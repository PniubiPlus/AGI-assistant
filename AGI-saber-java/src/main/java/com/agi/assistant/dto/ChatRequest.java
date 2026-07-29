package com.agi.assistant.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class ChatRequest {
    private String message;
    @JsonProperty("use_rag")
    private boolean useRag;
    @JsonProperty("selected_tools")
    private List<String> selectedTools;
    private boolean explicit;

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public boolean isUseRag() { return useRag; }
    public void setUseRag(boolean useRag) { this.useRag = useRag; }
    public List<String> getSelectedTools() { return selectedTools; }
    public void setSelectedTools(List<String> selectedTools) { this.selectedTools = selectedTools; }
    public boolean isExplicit() { return explicit; }
    public void setExplicit(boolean explicit) { this.explicit = explicit; }
}
