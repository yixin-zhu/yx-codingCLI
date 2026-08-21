package com.agent.cli;

/**
 * 计划审阅输入解析：Enter 执行 / esc 取消 / 其他文字视为补充要求。
 */
public final class PlanReviewInputParser {

    public enum DecisionType {
        EXECUTE,
        SUPPLEMENT,
        CANCEL
    }

    public record Decision(DecisionType type, String feedback) {
    }

    private PlanReviewInputParser() {
    }

    public static Decision parse(String input) {
        if (input != null && input.equals("\u001B")) {
            return new Decision(DecisionType.CANCEL, null);
        }

        String trimmed = input == null ? "" : input.trim();

        if (trimmed.isEmpty()
                || trimmed.equalsIgnoreCase("y")
                || trimmed.equalsIgnoreCase("yes")
                || trimmed.equalsIgnoreCase("run")
                || trimmed.equalsIgnoreCase("/run")) {
            return new Decision(DecisionType.EXECUTE, null);
        }

        if (trimmed.equalsIgnoreCase("cancel")
                || trimmed.equalsIgnoreCase("esc")
                || trimmed.equalsIgnoreCase("/cancel")) {
            return new Decision(DecisionType.CANCEL, null);
        }

        return new Decision(DecisionType.SUPPLEMENT, trimmed);
    }
}
