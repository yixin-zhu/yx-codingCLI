package com.agent.hitl;

import java.util.Set;

/**
 * 判断哪些工具调用需要 HITL 审批。
 */
public final class ApprovalPolicy {

    private static final Set<String> DANGEROUS_TOOLS = Set.of(
            "write_file",
            "execute_command",
            "create_project"
    );

    private ApprovalPolicy() {
    }

    public static boolean requiresApproval(String toolName) {
        return DANGEROUS_TOOLS.contains(toolName);
    }

    public static boolean shouldAudit(String toolName) {
        return requiresApproval(toolName);
    }

    public static String getDangerLevel(String toolName) {
        return switch (toolName) {
            case "execute_command" -> "🔴 高危";
            case "write_file", "create_project" -> "🟡 中危";
            default -> "🟢 安全";
        };
    }

    public static String getRiskDescription(String toolName) {
        return switch (toolName) {
            case "execute_command" -> "将在系统上执行 Shell 命令，可能修改文件或影响系统状态";
            case "write_file" -> "将写入或覆盖文件内容，原有内容将丢失";
            case "create_project" -> "将在磁盘上创建新目录和文件";
            default -> "安全的只读操作";
        };
    }

    public static Set<String> getDangerousTools() {
        return DANGEROUS_TOOLS;
    }
}
