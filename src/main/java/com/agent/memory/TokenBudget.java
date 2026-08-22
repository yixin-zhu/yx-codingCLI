package com.agent.memory;

import com.agent.llm.LlmClient;

import java.util.List;

public class TokenBudget {

    private static final int DEFAULT_CONTEXT_WINDOW = 128_000;
    private static final int DEFAULT_SYSTEM_RESERVE = 500;
    private static final int DEFAULT_TOOLS_RESERVE = 800;
    private static final int DEFAULT_RESPONSE_RESERVE = 2_000;

    private final int contextWindow;
    private final int reservedForSystem;
    private final int reservedForTools;
    private final int reservedForResponse;

    private int totalInputTokens;
    private int totalOutputTokens;
    private int totalCachedInputTokens;
    private int llmCallCount;

    public TokenBudget(int contextWindow) {
        this(contextWindow, DEFAULT_SYSTEM_RESERVE, DEFAULT_TOOLS_RESERVE, DEFAULT_RESPONSE_RESERVE);
    }

    public TokenBudget(int contextWindow, int reservedForSystem, int reservedForTools, int reservedForResponse) {
        this.contextWindow = contextWindow;
        this.reservedForSystem = reservedForSystem;
        this.reservedForTools = reservedForTools;
        this.reservedForResponse = reservedForResponse;
    }

    public static TokenBudget defaults() {
        return new TokenBudget(DEFAULT_CONTEXT_WINDOW);
    }

    public int getAvailableForConversation() {
        return contextWindow - reservedForSystem - reservedForTools - reservedForResponse;
    }

    public boolean needsCompression(ConversationMemory memory, double triggerRatio) {
        int compressionBudget = Math.min(memory.getMaxTokens(), getAvailableForConversation());
        return memory.getTokenCount() >= compressionBudget * triggerRatio;
    }

    public void recordUsage(int inputTokens, int outputTokens) {
        recordUsage(inputTokens, outputTokens, 0);
    }

    public void recordUsage(int inputTokens, int outputTokens, int cachedInputTokens) {
        totalInputTokens += inputTokens;
        totalOutputTokens += outputTokens;
        totalCachedInputTokens += Math.max(0, cachedInputTokens);
        llmCallCount++;
    }

    public String getUsageReport() {
        double avgInput = llmCallCount > 0 ? (double) totalInputTokens / llmCallCount : 0;
        return String.format(
                "Token 统计: 调用 %d 次 | 总输入: %d | 总输出: %d | cached: %d | 平均输入: %.0f | 预算: %d (可用: %d)",
                llmCallCount, totalInputTokens, totalOutputTokens, totalCachedInputTokens, avgInput,
                contextWindow, getAvailableForConversation()
        );
    }

    public int getContextWindow() {
        return contextWindow;
    }

    public int getTotalInputTokens() {
        return totalInputTokens;
    }

    public int getTotalOutputTokens() {
        return totalOutputTokens;
    }

    public int getTotalCachedInputTokens() {
        return totalCachedInputTokens;
    }

    public int getLlmCallCount() {
        return llmCallCount;
    }

    public static int estimateMessagesTokens(List<LlmClient.Message> messages) {
        if (messages == null) {
            return 0;
        }
        int total = 0;
        for (LlmClient.Message msg : messages) {
            total += MemoryEntry.estimateTokens(msg.content());
            if (msg.toolCalls() != null) {
                for (LlmClient.ToolCall toolCall : msg.toolCalls()) {
                    total += MemoryEntry.estimateTokens(toolCall.arguments());
                }
            }
        }
        total += messages.size() * 4;
        return total;
    }
}
