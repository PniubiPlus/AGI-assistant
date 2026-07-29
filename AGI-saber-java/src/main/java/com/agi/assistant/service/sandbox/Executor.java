package com.agi.assistant.service.sandbox;

/**
 * 沙箱执行器统一接口（对应 Go sandbox.Executor）
 */
public interface Executor {
    ExecResult exec(ExecRequest req);

    String backend();

    boolean available();
}
