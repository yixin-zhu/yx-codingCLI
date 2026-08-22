package com.agent.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.stream.Collectors;

@JsonIgnoreProperties(ignoreUnknown = true)
public record McpCallToolResult(List<McpContent> content, boolean isError) {

    public String formatForLlm() {
        if (content == null || content.isEmpty()) {
            return isError ? "MCP 工具返回错误，但没有错误正文" : "";
        }
        return content.stream()
                .map(this::formatItem)
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("\n\n"));
    }

    private String formatItem(McpContent item) {
        String type = item.type() == null || item.type().isBlank() ? "text" : item.type();
        if ("text".equals(type)) {
            return item.text() == null ? "" : item.text();
        }
        if ("image".equals(type)) {
            String mimeType = item.mimeType() == null || item.mimeType().isBlank()
                    ? "image/png"
                    : item.mimeType();
            int base64Length = item.data() == null ? 0 : item.data().length();
            return "[此工具返回了 image: mimeType=" + mimeType + ", base64Length=" + base64Length
                    + "；当前 Agent 仅支持文本回灌，请向用户描述结果]";
        }
        return "[此工具返回了 " + type + "，请向用户描述结果]";
    }
}
