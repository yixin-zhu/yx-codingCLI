package com.agent;

import com.agent.llm.LlmClient;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * ReAct 循环兜底预算：停滞检测 + 硬轮数上限。
 * 主退出条件仍由 LLM 决定（无 tool_calls 即结束）。
 */
public class AgentBudget {

    public enum ExitReason {
        WITHIN_BUDGET,
        STAGNATION_DETECTED,
        HARD_ITERATION_LIMIT
    }

    private static final int DEFAULT_STAGNATION_WINDOW = 3;
    private static final int DEFAULT_HARD_MAX_ITERATIONS = 50;

    private final int stagnationWindow;
    private final int hardMaxIterations;
    private final Deque<String> recentToolSignatures = new ArrayDeque<>();

    private int iteration;
    private int totalInputTokens;
    private int totalOutputTokens;
    private boolean stagnant;

    public AgentBudget(int stagnationWindow, int hardMaxIterations) {
        if (stagnationWindow < 2) {
            throw new IllegalArgumentException("stagnationWindow must be >= 2");
        }
        if (hardMaxIterations <= 0) {
            throw new IllegalArgumentException("hardMaxIterations must be positive");
        }
        this.stagnationWindow = stagnationWindow;
        this.hardMaxIterations = hardMaxIterations;
    }

    public static AgentBudget defaults() {
        return new AgentBudget(DEFAULT_STAGNATION_WINDOW, DEFAULT_HARD_MAX_ITERATIONS);
    }

    public int beginIteration() {
        return ++iteration;
    }

    public void recordTokens(int inputTokens, int outputTokens) {
        this.totalInputTokens += Math.max(0, inputTokens);
        this.totalOutputTokens += Math.max(0, outputTokens);
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

    public String describeExit(ExitReason reason) {
        return switch (reason) {
            case WITHIN_BUDGET -> "未触发兜底条件";
            case STAGNATION_DETECTED -> String.format(Locale.ROOT,
                    "检测到连续 %d 轮重复的工具调用，疑似死循环，已强制收尾", stagnationWindow);
            case HARD_ITERATION_LIMIT -> String.format(Locale.ROOT,
                    "达到硬轮数上限（%d），已强制收尾", hardMaxIterations);
        };
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
}
