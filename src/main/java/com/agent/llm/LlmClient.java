package com.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * LLM 客户端接口 - 定义与大语言模型交互的核心数据结构
 */
public interface LlmClient {

    ObjectMapper MAPPER = new ObjectMapper();

    // ==================== 数据结构 ====================

    /**
     * 消息角色
     */
    enum Role {
        SYSTEM, USER, ASSISTANT, TOOL
    }

    /**
     * 对话消息
     */
    record Message(
            String role,
            String content,
            String toolCallId,
            List<ToolCall> toolCalls
    ) {
        public static Message system(String content) {
            return new Message("system", content, null, null);
        }

        public static Message user(String content) {
            return new Message("user", content, null, null);
        }

        public static Message assistant(String content) {
            return new Message("assistant", content, null, null);
        }

        public static Message assistant(String content, List<ToolCall> toolCalls) {
            return new Message("assistant", content, null, toolCalls);
        }

        public static Message tool(String toolCallId, String content) {
            return new Message("tool", content, toolCallId, null);
        }
    }

    /**
     * 工具定义（注册给 LLM 的 JSON Schema）
     */
    record Tool(String name, String description, JsonNode parameters) {}

    /**
     * 工具函数调用（LLM 返回的 function call）
     */
    record ToolCall(String id, String name, String arguments) {}

    /**
     * LLM 响应
     */
    record ChatResponse(
            String role,
            String content,
            List<ToolCall> toolCalls,
            int inputTokens,
            int outputTokens,
            int cachedInputTokens
    ) {
        public ChatResponse(String role, String content, List<ToolCall> toolCalls,
                            int inputTokens, int outputTokens) {
            this(role, content, toolCalls, inputTokens, outputTokens, 0);
        }

        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }

    default String getModelName() {
        return "unknown";
    }

    default String getProviderName() {
        return "unknown";
    }

    default int maxContextWindow() {
        return 128_000;
    }

    default boolean supportsPromptCaching() {
        return false;
    }

    default String promptCacheMode() {
        return "none";
    }

    // ==================== 接口方法 ====================

    /**
     * 发送对话请求（非流式）
     *
     * @param messages 对话历史
     * @param tools    可用工具列表
     * @return LLM 响应
     */
    ChatResponse chat(List<Message> messages, List<Tool> tools);

    /**
     * 只发送消息（不注册工具）
     */
    default ChatResponse chat(List<Message> messages) {
        return chat(messages, List.of());
    }
}
