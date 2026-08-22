package com.agent.memory;

import com.agent.llm.LlmClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConversationHistoryCompactor {

    private static final Logger log = LoggerFactory.getLogger(ConversationHistoryCompactor.class);
    private static final int DEFAULT_RETAIN_RECENT_ROUNDS = 3;
    private static final int MAX_SUMMARY_INPUT_CHARS = 60_000;

    private static final String SUMMARY_PROMPT = """
            请把下面的对话历史压缩成简明摘要，保留：
            1. 用户提出的关键诉求与目标
            2. Agent 已经完成的关键操作
            3. 已经达成的共识或结论

            输出 1-3 段中文，不要用列表。

            === 待压缩的对话 ===
            %s
            === 待压缩的对话（结束）===
            """;

    private LlmClient llmClient;
    private final int retainRecentRounds;

    public ConversationHistoryCompactor(LlmClient llmClient) {
        this(llmClient, DEFAULT_RETAIN_RECENT_ROUNDS);
    }

    public ConversationHistoryCompactor(LlmClient llmClient, int retainRecentRounds) {
        this.llmClient = llmClient;
        this.retainRecentRounds = Math.max(1, retainRecentRounds);
    }

    public void setLlmClient(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public boolean compactIfNeeded(List<LlmClient.Message> history, int triggerTokens) {
        return compact(history, triggerTokens, false, retainRecentRounds);
    }

    public boolean compactNow(List<LlmClient.Message> history) {
        return compact(history, 0, true, 1);
    }

    private boolean compact(List<LlmClient.Message> history, int triggerTokens, boolean force, int retainRounds) {
        if (history == null || history.isEmpty()) {
            return false;
        }
        int currentTokens = TokenBudget.estimateMessagesTokens(history);
        if (!force && currentTokens < triggerTokens) {
            return false;
        }

        int systemEnd = "system".equals(history.get(0).role()) ? 1 : 0;
        List<Integer> userIndices = new ArrayList<>();
        for (int i = systemEnd; i < history.size(); i++) {
            if ("user".equals(history.get(i).role())) {
                userIndices.add(i);
            }
        }

        int effectiveRetainRounds = Math.max(1, retainRounds);
        if (userIndices.size() <= effectiveRetainRounds) {
            return false;
        }

        int splitIdx = userIndices.get(userIndices.size() - effectiveRetainRounds);
        if (splitIdx <= systemEnd) {
            return false;
        }

        List<LlmClient.Message> oldMsgs = new ArrayList<>(history.subList(systemEnd, splitIdx));
        if (oldMsgs.isEmpty()) {
            return false;
        }

        String summary;
        try {
            summary = summarize(oldMsgs);
        } catch (Exception e) {
            log.warn("conversation summary failed", e);
            return false;
        }
        if (summary == null || summary.isBlank()) {
            return false;
        }

        List<LlmClient.Message> rebuilt = new ArrayList<>();
        for (int i = 0; i < systemEnd; i++) {
            rebuilt.add(history.get(i));
        }
        rebuilt.add(LlmClient.Message.user("[已压缩的历史对话摘要]\n" + summary.trim()));
        rebuilt.add(LlmClient.Message.assistant("好的，我已了解之前的上下文，请继续。"));
        rebuilt.addAll(history.subList(splitIdx, history.size()));

        history.clear();
        history.addAll(rebuilt);
        log.info("compacted conversationHistory: tokens {} -> {}, messages {} -> {}",
                currentTokens, TokenBudget.estimateMessagesTokens(rebuilt), userIndices.size() + systemEnd, rebuilt.size());
        return true;
    }

    protected String summarize(List<LlmClient.Message> messages) {
        if (llmClient == null) {
            throw new IllegalStateException("LLM client not configured");
        }
        StringBuilder sb = new StringBuilder();
        for (LlmClient.Message message : messages) {
            sb.append(message.role().toUpperCase(Locale.ROOT)).append(": ");
            if (message.content() != null) {
                sb.append(message.content());
            }
            if (message.toolCalls() != null) {
                for (LlmClient.ToolCall toolCall : message.toolCalls()) {
                    sb.append("\n  TOOL_CALL ").append(toolCall.name())
                            .append(": ").append(toolCall.arguments());
                }
            }
            sb.append("\n\n");
            if (sb.length() > MAX_SUMMARY_INPUT_CHARS) {
                sb.append("...(超长内容已截断)\n");
                break;
            }
        }
        LlmClient.ChatResponse response = llmClient.chat(
                List.of(
                        LlmClient.Message.system("你是一个对话摘要助手，只输出摘要本身。"),
                        LlmClient.Message.user(String.format(SUMMARY_PROMPT, sb))
                ),
                null
        );
        return response == null ? null : response.content();
    }
}
