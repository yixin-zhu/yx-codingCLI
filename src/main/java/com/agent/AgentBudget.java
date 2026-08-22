package com.agent;

import com.agent.context.ContextProfile;
import com.agent.llm.LlmClient;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * ReAct 循环兜底预算：停滞检测 + 可选 token 硬限 + 硬轮数上限。
 * 主退出条件仍由 LLM 决定（无 tool_calls 即结束）。
 * token 硬限默认无限；显式 -Dagent.react.token.budget=N 可启用。
 */
public class AgentBudget {

    public enum ExitReason {
        WITHIN_BUDGET,
        TOKEN_BUDGET_EXCEEDED,
        STAGNATION_DETECTED,
        HARD_ITERATION_LIMIT
    }

    private static final int DEFAULT_STAGNATION_WINDOW = 3;
    private static final int DEFAULT_HARD_MAX_ITERATIONS = 50;

    private final int tokenBudget;
    private final int stagnationWindow;
    private final int hardMaxIterations;
    private final Deque<String> recentToolSignatures = new ArrayDeque<>();

    private int iteration;
    private int totalInputTokens;
    private int totalOutputTokens;
    private int totalCachedInputTokens;
    private boolean stagnant;

    public AgentBudget(int stagnationWindow, int hardMaxIterations) {
        this(Integer.MAX_VALUE, stagnationWindow, hardMaxIterations);
    }

    public AgentBudget(int tokenBudget, int stagnationWindow, int hardMaxIterations) {
        if (tokenBudget <= 0) {
            throw new IllegalArgumentException("tokenBudget must be positive");
        }
        if (stagnationWindow < 2) {
            throw new IllegalArgumentException("stagnationWindow must be >= 2");
        }
        if (hardMaxIterations <= 0) {
            throw new IllegalArgumentException("hardMaxIterations must be positive");
        }
        this.tokenBudget = tokenBudget;
        this.stagnationWindow = stagnationWindow;
        this.hardMaxIterations = hardMaxIterations;
    }

    public static AgentBudget defaults() {
        return fromLlmClient(null);
    }

    public static AgentBudget fromLlmClient(LlmClient llmClient) {
        return new AgentBudget(
                readIntProperty("agent.react.token.budget", Integer.MAX_VALUE),
                readIntProperty("agent.react.stagnation.window", DEFAULT_STAGNATION_WINDOW),
                readIntProperty("agent.react.hard.max.iterations", DEFAULT_HARD_MAX_ITERATIONS)
        );
    }

    /** 软预算提示值（用于 /context 展示，非硬限） */
    public static int softTokenBudgetHint(LlmClient llmClient) {
        return ContextProfile.from(llmClient).agentTokenBudget();
    }

    public int beginIteration() {
        return ++iteration;
    }

    public void recordTokens(int inputTokens, int outputTokens) {
        recordTokens(inputTokens, outputTokens, 0);
    }

    public void recordTokens(int inputTokens, int outputTokens, int cachedInputTokens) {
        this.totalInputTokens += Math.max(0, inputTokens);
        this.totalOutputTokens += Math.max(0, outputTokens);
        this.totalCachedInputTokens += Math.max(0, cachedInputTokens);
    }

    public void recordToolCalls(List<LlmClient.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            recentToolSignatures.clear();
            stagnant = false;
            return;
        }
        recentToolSignatures.addLast(signatureOf(toolCalls));
        while (recentToolSignatures.size() > stagnationWindow) {
            recentToolSignatures.removeFirst();
        }
        if (recentToolSignatures.size() == stagnationWindow) {
            String first = recentToolSignatures.peekFirst();
            stagnant = recentToolSignatures.stream().allMatch(sig -> sig.equals(first));
        }
    }

    public ExitReason check() {
        if (stagnant) {
            return ExitReason.STAGNATION_DETECTED;
        }
        if (totalInputTokens + totalOutputTokens >= tokenBudget) {
            return ExitReason.TOKEN_BUDGET_EXCEEDED;
        }
        if (iteration >= hardMaxIterations) {
            return ExitReason.HARD_ITERATION_LIMIT;
        }
        return ExitReason.WITHIN_BUDGET;
    }

    public int iteration() {
        return iteration;
    }

    public int totalInputTokens() {
        return totalInputTokens;
    }

    public int totalOutputTokens() {
        return totalOutputTokens;
    }

    public int totalCachedInputTokens() {
        return totalCachedInputTokens;
    }

    public int tokenBudget() {
        return tokenBudget;
    }

    public int hardMaxIterations() {
        return hardMaxIterations;
    }

    public int stagnationWindow() {
        return stagnationWindow;
    }

    public String describeExit(ExitReason reason) {
        return switch (reason) {
            case WITHIN_BUDGET -> "未触发兜底条件";
            case TOKEN_BUDGET_EXCEEDED -> String.format(Locale.ROOT,
                    "Token 预算已用尽（%d / %d），任务被强制收尾",
                    totalInputTokens + totalOutputTokens, tokenBudget);
            case STAGNATION_DETECTED -> String.format(Locale.ROOT,
                    "检测到连续 %d 轮重复的工具调用，疑似死循环，已强制收尾", stagnationWindow);
            case HARD_ITERATION_LIMIT -> String.format(Locale.ROOT,
                    "达到硬轮数上限（%d），已强制收尾", hardMaxIterations);
        };
    }

    public String formatRunStats(LlmClient llmClient) {
        int softHint = softTokenBudgetHint(llmClient);
        String budgetLine = tokenBudget == Integer.MAX_VALUE
                ? String.format(Locale.ROOT, "本轮 token: 输入 %d | 输出 %d | cached %d | 软提示预算 %d（无硬限）",
                totalInputTokens, totalOutputTokens, totalCachedInputTokens, softHint)
                : String.format(Locale.ROOT, "本轮 token: 输入 %d | 输出 %d | cached %d | 硬限 %d / %d",
                totalInputTokens, totalOutputTokens, totalCachedInputTokens,
                totalInputTokens + totalOutputTokens, tokenBudget);
        return budgetLine;
    }

    private static String signatureOf(List<LlmClient.ToolCall> toolCalls) {
        StringBuilder sb = new StringBuilder();
        for (LlmClient.ToolCall toolCall : toolCalls) {
            sb.append(toolCall.name())
                    .append('|')
                    .append(toolCall.arguments())
                    .append(';');
        }
        return sb.toString();
    }

    private static int readIntProperty(String key, int defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
