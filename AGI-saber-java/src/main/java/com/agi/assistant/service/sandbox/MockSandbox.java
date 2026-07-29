package com.agi.assistant.service.sandbox;

/**
 * MockSandbox 返回固定结果，用于测试或沙箱完全不可用时占位（对应 Go sandbox.MockSandbox）
 */
public class MockSandbox implements Executor {

    @Override
    public String backend() { return "mock"; }

    @Override
    public boolean available() { return true; }

    @Override
    public ExecResult exec(ExecRequest req) {
        ExecResult r = new ExecResult();
        r.setCommand(req.getCommand());
        r.setBackend("mock");
        r.setStdout("[mock] 命令 \"" + req.getCommand()
                + "\" 在模拟沙箱中执行（Docker 不可用）");
        r.setExitCode(0);
        r.setDurationMs(1);
        return r;
    }
}
