package com.agi.assistant.service.sandbox;

import com.agi.assistant.config.AppConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validator 对命令做静态安全校验，输出 SAFE / WARN / BLOCK 三种级别。
 * 对应 Go internal/sandbox/validator.go
 */
public class Validator {

    /** Block 级规则：任意一条命中即拒绝 */
    private static final List<Rule> BLOCK_RULES = List.of(
            // 文件系统破坏
            r("rm\\s+(-[rfRF]+\\s+)?/", "禁止删除根路径"),
            r("rm\\s+-[rfRF]*r[fF]*\\s", "禁止 rm -rf"),
            r("\\bdd\\s+if=", "禁止 dd 设备写入"),
            r("\\bmkfs\\b", "禁止格式化文件系统"),
            r(">\\s*/dev/(sd|hd|nvme|vd|xvd)", "禁止写入块设备"),
            r(":\\s*\\(\\s*\\)\\s*\\{.*:\\s*\\|", "禁止 Fork 炸弹"),
            // 权限提升
            r("\\bsudo\\b", "禁止 sudo"),
            r("\\bsu\\s", "禁止 su"),
            r("\\bchmod\\s+[0-7]*7[0-7][0-7]\\b", "禁止 chmod 777"),
            r("\\bchown\\s+root\\b", "禁止变更为 root 属主"),
            // 系统控制
            r("\\b(shutdown|reboot|halt|poweroff|init 0)\\b", "禁止系统关机/重启"),
            r("\\bsystemctl\\s+(stop|disable|mask)\\b", "禁止停止系统服务"),
            r("\\biptables\\b", "禁止修改防火墙规则"),
            // Shell 注入风险
            r("\\$\\(", "禁止命令替换 $()"),
            r("`", "禁止反引号命令替换"),
            r("\\beval\\b", "禁止 eval"),
            // 敏感文件
            r("/etc/(passwd|shadow|sudoers|ssh)", "禁止访问系统凭证文件"),
            r("~/?\\.(ssh|aws|docker|kube)/", "禁止访问凭证目录"),
            // 路径遍历
            r("\\.\\./\\.\\./", "禁止多级路径遍历"),
            // 网络外联
            r("\\b(curl|wget|nc|netcat|ncat)\\s.*http", "禁止网络外联（沙箱无网）"),
            r("\\bssh\\b", "禁止 SSH 连接"),
            // 进程/资源滥用
            r("\\bkillall\\b", "禁止 killall"),
            r("\\bnohup\\b", "禁止 nohup 后台驻留")
    );

    /** Warn 级规则：收集所有命中项 */
    private static final List<Rule> WARN_RULES = List.of(
            r("\\brm\\s", "删除文件操作"),
            r(">\\s*\\w", "输出重定向（可能覆盖文件）"),
            r("\\bkill\\s", "进程终止操作"),
            r("\\bpip\\s+install\\b", "安装 Python 包"),
            r("\\bnpm\\s+install\\b", "安装 Node 包"),
            r("\\bapt(-get)?\\s+install\\b", "安装系统包"),
            r("\\bapk\\s+add\\b", "安装 Alpine 包"),
            r(";\\s*\\S", "命令链（分号分隔）"),
            r("\\|", "管道符"),
            r("&&", "条件命令链 &&"),
            r("\\|\\|", "条件命令链 ||")
    );

    private final AppConfig.SecurityConfig cfg;

    public Validator(AppConfig.SecurityConfig cfg) {
        this.cfg = cfg;
    }

    public ValidationResult validate(String command) {
        // 1. 长度
        if (command != null && command.length() > cfg.getMaxCommandLength()) {
            return new ValidationResult(RiskLevel.BLOCK, "命令超过最大长度限制");
        }
        // 2. 空命令
        if (command == null || command.trim().isEmpty()) {
            return new ValidationResult(RiskLevel.BLOCK, "命令不能为空");
        }
        // 3. 白名单
        if (cfg.isAllowlistMode() && cfg.getAllowlist() != null && !cfg.getAllowlist().isEmpty()) {
            String[] tokens = command.trim().split("\\s+", 2);
            String first = tokens.length > 0 ? tokens[0] : "";
            boolean allowed = false;
            for (String a : cfg.getAllowlist()) {
                if (a.equalsIgnoreCase(first)) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                return new ValidationResult(RiskLevel.BLOCK,
                        "白名单模式：命令 \"" + first + "\" 未在允许列表中");
            }
        }
        // 4. Block 规则
        for (Rule rule : BLOCK_RULES) {
            if (rule.pattern.matcher(command).find()) {
                return new ValidationResult(RiskLevel.BLOCK, rule.text);
            }
        }
        // 5. Warn 规则
        List<String> violations = new ArrayList<>();
        for (Rule rule : WARN_RULES) {
            if (rule.pattern.matcher(command).find()) {
                violations.add(rule.text);
            }
        }
        if (!violations.isEmpty()) {
            ValidationResult v = new ValidationResult(RiskLevel.WARN);
            v.setViolations(violations);
            return v;
        }
        return new ValidationResult(RiskLevel.SAFE);
    }

    private static Rule r(String pattern, String text) {
        return new Rule(Pattern.compile(pattern), text);
    }

    private record Rule(Pattern pattern, String text) {}
}
