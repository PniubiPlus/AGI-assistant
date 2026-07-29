package com.agi.assistant.model;

import java.util.List;

public class TaskState {
    private String taskId;
    private String query;
    private String status;
    private String phase;
    private List<TaskStep> steps;
    private int currentStep;
    private int interruptedAt;
    private String result;

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }
    public List<TaskStep> getSteps() { return steps; }
    public void setSteps(List<TaskStep> steps) { this.steps = steps; }
    public int getCurrentStep() { return currentStep; }
    public void setCurrentStep(int currentStep) { this.currentStep = currentStep; }
    public int getInterruptedAt() { return interruptedAt; }
    public void setInterruptedAt(int interruptedAt) { this.interruptedAt = interruptedAt; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
}
