package com.agi.assistant.service.sandbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;

/**
 * 一次命令执行的完整结果（对应 Go sandbox.ExecResult）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExecResult {
    private String command;
    private ValidationResult validation;
    private String stdout = "";
    private String stderr = "";
    @JsonProperty("exit_code")
    private int exitCode;
    /** 单位：纳秒 -> 毫秒 */
    private long durationMs;
    private boolean killed;
    private String backend;
    private boolean truncated;

    public ExecResult() {}

    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }
    public ValidationResult getValidation() { return validation; }
    public void setValidation(ValidationResult validation) { this.validation = validation; }
    public String getStdout() { return stdout; }
    public void setStdout(String stdout) { this.stdout = stdout; }
    public String getStderr() { return stderr; }
    public void setStderr(String stderr) { this.stderr = stderr; }
    public int getExitCode() { return exitCode; }
    public void setExitCode(int exitCode) { this.exitCode = exitCode; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public Duration getDuration() { return Duration.ofMillis(durationMs); }
    public boolean isKilled() { return killed; }
    public void setKilled(boolean killed) { this.killed = killed; }
    public String getBackend() { return backend; }
    public void setBackend(String backend) { this.backend = backend; }
    public boolean isTruncated() { return truncated; }
    public void setTruncated(boolean truncated) { this.truncated = truncated; }
}
