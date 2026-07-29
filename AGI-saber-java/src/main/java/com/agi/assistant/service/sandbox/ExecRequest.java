package com.agi.assistant.service.sandbox;

import java.time.Duration;

/**
 * 一次命令执行请求（对应 Go sandbox.ExecRequest）
 */
public class ExecRequest {
    private String command;
    /** 0 表示使用 Sandbox 默认值 */
    private Duration timeout;
    /** 对 Warn 级别命令的二次确认标记 */
    private boolean confirm;

    public ExecRequest() {}

    public ExecRequest(String command) {
        this.command = command;
    }

    public ExecRequest(String command, Duration timeout, boolean confirm) {
        this.command = command;
        this.timeout = timeout;
        this.confirm = confirm;
    }

    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public boolean isConfirm() { return confirm; }
    public void setConfirm(boolean confirm) { this.confirm = confirm; }
}
