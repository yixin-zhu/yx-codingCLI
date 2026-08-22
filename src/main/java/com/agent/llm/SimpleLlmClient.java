package com.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 简易 LLM 客户端实现 - 支持 OpenAI 兼容的 API（非流式）
 */
public class SimpleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(SimpleLlmClient.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final String apiUrl;
    private final String apiKey;
    private final String model;
    private final OkHttpClient httpClient;

    public SimpleLlmClient(String apiUrl, String apiKey, String model) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model != null && !model.isBlank() ? model : "deepseek-chat";
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    public String getProviderName() {
        return "deepseek";
    }

    @Override
    public int maxContextWindow() {
        String normalized = model.toLowerCase();
        if (normalized.contains("v4") || normalized.contains("1m") || normalized.contains("million")) {
            return 1_000_000;
        }
        return 128_000;
    }

    @Override
    public boolean supportsPromptCaching() {
        return true;
    }

    @Override
    public String promptCacheMode() {
        return "automatic-prefix-cache";
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools) {
        try {
            // 构建请求体
            ObjectNode requestBody = MAPPER.createObjectNode();
            requestBody.put("model", model);
            requestBody.put("stream", false);

            // 添加消息
            ArrayNode messagesArray = requestBody.putArray("messages");
            for (Message msg : messages) {
                messagesArray.add(messageToJson(msg));
            }

            // 添加工具定义
            if (tools != null && !tools.isEmpty()) {
                ArrayNode toolsArray = requestBody.putArray("tools");
                for (Tool tool : tools) {
                    toolsArray.add(toolToJson(tool));
                }
                requestBody.put("tool_choice", "auto");
            }

            // 发送请求
            String json = MAPPER.writeValueAsString(requestBody);
            log.debug("Request body: {}", json);

            Request request = new Request.Builder()
                    .url(apiUrl + "/chat/completions")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(json, JSON))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "No body";
                    throw new RuntimeException("LLM API 调用失败: HTTP " + response.code() + " - " + errorBody);
                }

                String responseBody = response.body().string();
                log.debug("Response body: {}", responseBody);
                return parseResponse(responseBody);
            }

        } catch (IOException e) {
            log.error("LLM API 调用异常", e);
            throw new RuntimeException("LLM API 调用异常: " + e.getMessage(), e);
        }
    }

    /**
     * 转换消息为 JSON
     */
    private ObjectNode messageToJson(Message msg) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("role", msg.role());

        if ("tool".equals(msg.role())) {
            // tool 消息格式
            node.put("content", msg.content());
            node.put("tool_call_id", msg.toolCallId());
        } else {
            // 处理 content
            if (msg.content() == null) {
                node.putNull("content");
            } else {
                node.put("content", msg.content());
            }
            
            // 如果有 tool_calls，需要添加（assistant 消息调用工具时）
            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                ArrayNode toolCallsArray = node.putArray("tool_calls");
                for (LlmClient.ToolCall tc : msg.toolCalls()) {
                    ObjectNode tcNode = MAPPER.createObjectNode();
                    tcNode.put("id", tc.id());
                    tcNode.put("type", "function");
                    ObjectNode funcNode = MAPPER.createObjectNode();
                    funcNode.put("name", tc.name());
                    funcNode.put("arguments", tc.arguments());
                    tcNode.set("function", funcNode);
                    toolCallsArray.add(tcNode);
                }
            }
        }

        return node;
    }

    /**
     * 转换工具定义为 JSON
     */
    private ObjectNode toolToJson(Tool tool) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "function");

        ObjectNode functionNode = MAPPER.createObjectNode();
        functionNode.put("name", tool.name());
        functionNode.put("description", tool.description());
        functionNode.set("parameters", tool.parameters());

        node.set("function", functionNode);
        return node;
    }

    /**
     * 解析 LLM 响应
     */
    private ChatResponse parseResponse(String responseBody) throws IOException {
        JsonNode root = MAPPER.readTree(responseBody);

        // 解析 token usage
        int inputTokens = 0;
        int outputTokens = 0;
        int cachedInputTokens = 0;
        if (root.has("usage")) {
            JsonNode usage = root.get("usage");
            inputTokens = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt() : 0;
            outputTokens = usage.has("completion_tokens") ? usage.get("completion_tokens").asInt() : 0;
            if (usage.has("prompt_tokens_details")) {
                JsonNode details = usage.get("prompt_tokens_details");
                if (details.has("cached_tokens")) {
                    cachedInputTokens = details.get("cached_tokens").asInt();
                }
            }
            if (cachedInputTokens == 0 && usage.has("prompt_cache_hit_tokens")) {
                cachedInputTokens = usage.get("prompt_cache_hit_tokens").asInt();
            }
        }

        // 解析 choice
        JsonNode choices = root.get("choices");
        if (choices == null || choices.isEmpty()) {
            return new ChatResponse("assistant", "", List.of(), inputTokens, outputTokens, cachedInputTokens);
        }

        JsonNode choice = choices.get(0);
        JsonNode messageNode = choice.get("message");

        String role = messageNode.has("role") ? messageNode.get("role").asText() : "assistant";
        String content = messageNode.has("content") && !messageNode.get("content").isNull()
                ? messageNode.get("content").asText()
                : "";

        // 解析 tool_calls
        List<ToolCall> toolCalls = new ArrayList<>();
        if (messageNode.has("tool_calls") && messageNode.get("tool_calls").isArray()) {
            for (JsonNode tcNode : messageNode.get("tool_calls")) {
                String id = tcNode.get("id").asText();
                JsonNode function = tcNode.get("function");
                String name = function.get("name").asText();
                String arguments = function.get("arguments").asText();
                toolCalls.add(new ToolCall(id, name, arguments));
            }
        }

        return new ChatResponse(role, content, toolCalls, inputTokens, outputTokens, cachedInputTokens);
    }
}
