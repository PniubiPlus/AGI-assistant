package com.agi.assistant.service.sandbox;

import com.agi.assistant.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * LocalSandbox 在本机直接执行命令（无容器隔离），仅用于 Docker 不可用时的降级场景。
 * 出于安全考虑，LocalSandbox 始终对命令做二次 Block 校验，且超时强制终止。
 * 对应 Go internal/sandbox/local.go
 */
public class LocalSandbox implements Executor {

    private static final Logger log = LoggerFactory.getLogger(LocalSandbox.class);

    private final AppConfig.SandboxConfig cfg;
    private final Validator validator;

    public LocalSandbox(AppConfig.SandboxConfig cfg) {
        this.cfg = cfg;
        AppConfig.SecurityConfig sec = new AppConfig.SecurityConfig();
        sec.setMaxCommandLength(cfg.getMaxOutputBytes() > 0 ? cfg.getMaxOutputBytes() : 500);
        this.validator = new Validator(sec);
    }

    @Override
    public String backend() { return "local"; }

    @Override
    public boolean available() { return true; }

    @Override
    public ExecResult exec(ExecRequest req) {
        long start = System.currentTimeMillis();
        ExecResult result = new ExecResult();
        result.setCommand(req.getCommand());
        result.setBackend("local");

        Duration timeout = req.getTimeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = Duration.ofMillis(cfg.getTimeoutMs());
        }
        if (timeout.isZero() || timeout.isNegative()) {
            timeout = Duration.ofSeconds(15);
        }

        // 本地模式只允许 SAFE 级别命令
        ValidationResult v = validator.validate(req.getCommand());
        if (v.getLevel() != RiskLevel.SAFE) {
            result.setExitCode(-1);
            result.setStderr("[本地模式拒绝] 只允许 safe 级别命令，当前: "
                    + v.getLevel().value() + " " + v.getViolations());
            return result;
        }

        try {
            List<String> args = new ArrayList<>();
            args.add("sh");
            args.add("-c");
            args.add(req.getCommand());
            ProcessBuilder pb = new ProcessBuilder(args);
            Process proc = pb.start();
            ByteArrayOutputStream stdoutBuf = new ByteArrayOutputStream();
            ByteArrayOutputStream stderrBuf = new ByteArrayOutputStream();
            int maxOut = cfg.getMaxOutputBytes();

            Thread tOut = DockerSandbox.pumpStream(proc.getInputStream(), stdoutBuf, maxOut);
            Thread tErr = DockerSandbox.pumpStream(proc.getErrorStream(), stderrBuf, maxOut);

            boolean finished = proc.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            result.setDurationMs(System.currentTimeMillis() - start);

            if (!finished) {
                proc.destroyForcibly();
                tOut.join(500);
                tErr.join(500);
                result.setStdout(stdoutBuf.toString());
                result.setStderr(stderrBuf.toString() + "\n[超时] 执行超过 " + timeout + " 被终止");
                result.setKilled(true);
                result.setExitCode(-4);
                return result;
            }
            tOut.join();
            tErr.join();

            result.setStdout(stdoutBuf.toString());
            result.setStderr(stderrBuf.toString());
            result.setExitCode(proc.exitValue());
            if (maxOut > 0 && (stdoutBuf.size() >= maxOut || stderrBuf.size() >= maxOut)) {
                result.setTruncated(true);
            }
            return result;
        } catch (Exception e) {
            log.warn("Local exec 失败: {}", e.getMessage());
            result.setExitCode(-5);
            result.setStderr(result.getStderr() + "\n" + e.getMessage());
            result.setDurationMs(System.currentTimeMillis() - start);
            return result;
        }
    }
}
