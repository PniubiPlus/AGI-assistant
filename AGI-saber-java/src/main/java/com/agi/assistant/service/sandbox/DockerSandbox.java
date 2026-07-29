package com.agi.assistant.service.sandbox;

import com.agi.assistant.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * DockerSandbox 通过 docker CLI 在容器内执行命令（对应 Go internal/sandbox/docker.go）
 *
 * 关键安全约束（作为 docker run 参数传入）:
 *   --rm                  执行完自动清理容器
 *   --network none        禁用网络
 *   --read-only           根文件系统只读
 *   --tmpfs /tmp:size=...允许 /tmp 临时写入
 *   --memory / --cpus / --pids-limit  cgroup 资源硬限制
 *   --security-opt no-new-privileges  禁止权限提升
 *   --cap-drop ALL        放弃所有 Linux capabilities
 */
public class DockerSandbox implements Executor {

    private static final Logger log = LoggerFactory.getLogger(DockerSandbox.class);

    private final AppConfig.SandboxConfig cfg;
    private final boolean available;

    public DockerSandbox(AppConfig.SandboxConfig cfg) {
        this.cfg = cfg;
        this.available = probe();
    }

    @Override
    public String backend() { return "docker"; }

    @Override
    public boolean available() { return available; }

    private boolean probe() {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public ExecResult exec(ExecRequest req) {
        long start = System.currentTimeMillis();
        ExecResult result = new ExecResult();
        result.setCommand(req.getCommand());
        result.setBackend("docker");

        if (!available) {
            result.setExitCode(-3);
            result.setStderr("Docker 后端不可用");
            return result;
        }

        Duration timeout = req.getTimeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = Duration.ofMillis(cfg.getTimeoutMs());
        }
        if (timeout.isZero() || timeout.isNegative()) {
            timeout = Duration.ofSeconds(30);
        }

        List<String> args = buildDockerArgs(req.getCommand());
        ProcessBuilder pb = new ProcessBuilder(args);

        try {
            Process proc = pb.start();
            ByteArrayOutputStream stdoutBuf = new ByteArrayOutputStream();
            ByteArrayOutputStream stderrBuf = new ByteArrayOutputStream();
            int maxOut = cfg.getMaxOutputBytes();

            Thread tOut = pumpStream(proc.getInputStream(), stdoutBuf, maxOut);
            Thread tErr = pumpStream(proc.getErrorStream(), stderrBuf, maxOut);

            boolean finished = proc.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            long duration = System.currentTimeMillis() - start;
            result.setDurationMs(duration);

            if (!finished) {
                proc.destroyForcibly();
                tOut.join(500);
                tErr.join(500);
                result.setStdout(stdoutBuf.toString());
                result.setStderr(appendIfMissing(stderrBuf.toString(),
                        "\n[超时] 执行超过 " + timeout + " 被强制终止"));
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
            log.warn("Docker exec 失败: {}", e.getMessage());
            result.setExitCode(-5);
            result.setStderr(result.getStderr() + "\n[沙箱内部错误] " + e.getMessage());
            result.setDurationMs(System.currentTimeMillis() - start);
            return result;
        }
    }

    private List<String> buildDockerArgs(String command) {
        List<String> args = new ArrayList<>();
        args.add("docker");
        args.add("run");
        args.add("--rm");
        args.add("-i");
        args.add("--security-opt");
        args.add("no-new-privileges");
        args.add("--cap-drop");
        args.add("ALL");
        if (cfg.isNetworkDisabled()) {
            args.add("--network");
            args.add("none");
        }
        if (cfg.isReadonlyRootfs()) {
            args.add("--read-only");
            args.add("--tmpfs");
            args.add("/tmp:rw,size=64m");
        }
        if (cfg.getMemoryLimitMb() > 0) {
            args.add("--memory");
            args.add(cfg.getMemoryLimitMb() + "m");
        }
        if (cfg.getCpuPercent() > 0) {
            args.add("--cpus");
            args.add(String.format("%.2f", cfg.getCpuPercent() / 100.0));
        }
        if (cfg.getMaxPids() > 0) {
            args.add("--pids-limit");
            args.add(String.valueOf(cfg.getMaxPids()));
        }
        String image = cfg.getImage();
        if (image == null || image.isEmpty()) image = "alpine:3.19";
        args.add(image);
        args.add("sh");
        args.add("-c");
        args.add(command);
        return args;
    }

    static Thread pumpStream(InputStream in, OutputStream out, int max) {
        Thread t = new Thread(() -> {
            try (in) {
                byte[] buf = new byte[4096];
                int total = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (max > 0 && total >= max) {
                        // drain but don't write
                        continue;
                    }
                    int writable = max > 0 ? Math.min(n, max - total) : n;
                    if (writable > 0) {
                        out.write(buf, 0, writable);
                        total += writable;
                    }
                }
            } catch (Exception ignored) {
            }
        }, "sandbox-pump");
        t.setDaemon(true);
        t.start();
        return t;
    }

    static String appendIfMissing(String base, String suffix) {
        if (base == null) return suffix;
        if (base.contains("超时")) return base;
        return base + suffix;
    }
}
