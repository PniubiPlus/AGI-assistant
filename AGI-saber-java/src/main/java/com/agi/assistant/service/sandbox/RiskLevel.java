package com.agi.assistant.service.sandbox;

/**
 * 命令安全校验的风险级别（对应 Go sandbox.RiskLevel）
 */
public enum RiskLevel {
    SAFE("safe"),
    WARN("warn"),
    BLOCK("block");

    private final String value;

    RiskLevel(String value) { this.value = value; }

    public String value() { return value; }
}
