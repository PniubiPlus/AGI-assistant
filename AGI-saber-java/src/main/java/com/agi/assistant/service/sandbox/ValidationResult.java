package com.agi.assistant.service.sandbox;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * 单次安全校验的结果（对应 Go sandbox.ValidationResult）
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class ValidationResult {
    private RiskLevel level = RiskLevel.SAFE;
    private List<String> violations = new ArrayList<>();
    private String reason;

    public ValidationResult() {}

    public ValidationResult(RiskLevel level) {
        this.level = level;
    }

    public ValidationResult(RiskLevel level, String reason) {
        this.level = level;
        this.reason = reason;
    }

    public RiskLevel getLevel() { return level; }
    public void setLevel(RiskLevel level) { this.level = level; }
    public List<String> getViolations() { return violations; }
    public void setViolations(List<String> violations) {
        this.violations = violations != null ? violations : new ArrayList<>();
    }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
