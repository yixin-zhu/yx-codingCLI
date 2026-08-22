package com.agent.memory;

import com.agent.llm.LlmClient;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ContextCompressor {

    private static final String MAP_PROMPT = """
            请将以下对话片段压缩成一段简洁的摘要，保留关键信息：
            - 用户的需求和意图
            - 已执行的操作和结果
            - 做出的决策和结论

            对话片段：
            %s

            请用中文输出摘要，控制在200字以内。
            """;

    private LlmClient llmClient;
    private final int retainRecentRounds;

    public ContextCompressor(LlmClient llmClient) {
        this(llmClient, 3);
    }

    public ContextCompressor(LlmClient llmClient, int retainRecentRounds) {
        this.llmClient = llmClient;
        this.retainRecentRounds = retainRecentRounds;
    }

    public void setLlmClient(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public String compress(ConversationMemory memory) {
        List<MemoryEntry> allEntries = memory.getAll();
        if (allEntries.size() <= retainRecentRounds) {
            return null;
        }

        int splitPoint = allEntries.size() - retainRecentRounds;
        List<MemoryEntry> oldEntries = new ArrayList<>(allEntries.subList(0, splitPoint));
        List<MemoryEntry> recentEntries = new ArrayList<>(allEntries.subList(splitPoint, allEntries.size()));

        StringBuilder chunkText = new StringBuilder();
        for (MemoryEntry entry : oldEntries) {
            chunkText.append(entry.getType()).append(": ")
                    .append(entry.getContent()).append("\n\n");
        }

        String finalSummary;
        try {
            String prompt = String.format(MAP_PROMPT, chunkText);
            LlmClient.ChatResponse response = llmClient.chat(
                    List.of(
                            LlmClient.Message.system("你是一个对话摘要助手。"),
                            LlmClient.Message.user(prompt)
                    ),
                    null
            );
            finalSummary = response.content();
        } catch (Exception e) {
            finalSummary = chunkText.substring(0, Math.min(200, chunkText.length()));
        }

        if (finalSummary == null || finalSummary.isBlank()) {
            return null;
        }

        memory.clear();
        MemoryEntry summaryEntry = new MemoryEntry(
                "summary-" + UUID.randomUUID().toString().substring(0, 8),
                "[历史对话摘要] " + finalSummary,
                MemoryEntry.MemoryType.SUMMARY,
                null,
                MemoryEntry.estimateTokens(finalSummary)
        );
        memory.store(summaryEntry);
        for (MemoryEntry entry : recentEntries) {
            memory.store(entry);
        }
        return finalSummary;
    }
}
