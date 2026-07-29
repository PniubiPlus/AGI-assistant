package com.agi.assistant.dto;

import com.agi.assistant.model.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatResponse {
    private String query;
    private String answer;
    private String mode;
    private List<ReActStep> steps;
    @JsonProperty("tool_call")
    private ToolCallResult toolCall;
    @JsonProperty("search_results")
    private List<SearchResultDto> searchResults;
    private TaskState task;
    @JsonProperty("extracted_info")
    private String extractedInfo;
    @JsonProperty("short_term_count")
    private int shortTermCount;
    @JsonProperty("long_term_count")
    private int longTermCount;
    private Map<String, String> preferences;
    private Boolean interrupted;

    // getters/setters
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public List<ReActStep> getSteps() { return steps; }
    public void setSteps(List<ReActStep> steps) { this.steps = steps; }
    public ToolCallResult getToolCall() { return toolCall; }
    public void setToolCall(ToolCallResult toolCall) { this.toolCall = toolCall; }
    public List<SearchResultDto> getSearchResults() { return searchResults; }
    public void setSearchResults(List<SearchResultDto> searchResults) { this.searchResults = searchResults; }
    public TaskState getTask() { return task; }
    public void setTask(TaskState task) { this.task = task; }
    public String getExtractedInfo() { return extractedInfo; }
    public void setExtractedInfo(String extractedInfo) { this.extractedInfo = extractedInfo; }
    public int getShortTermCount() { return shortTermCount; }
    public void setShortTermCount(int shortTermCount) { this.shortTermCount = shortTermCount; }
    public int getLongTermCount() { return longTermCount; }
    public void setLongTermCount(int longTermCount) { this.longTermCount = longTermCount; }
    public Map<String, String> getPreferences() { return preferences; }
    public void setPreferences(Map<String, String> preferences) { this.preferences = preferences; }
    public Boolean getInterrupted() { return interrupted; }
    public void setInterrupted(Boolean interrupted) { this.interrupted = interrupted; }

    public static class SearchResultDto {
        private Chunk chunk;
        private double similarity;
        public SearchResultDto() {}
        public SearchResultDto(Chunk chunk, double similarity) { this.chunk = chunk; this.similarity = similarity; }
        public Chunk getChunk() { return chunk; }
        public void setChunk(Chunk chunk) { this.chunk = chunk; }
        public double getSimilarity() { return similarity; }
        public void setSimilarity(double similarity) { this.similarity = similarity; }
    }
}
