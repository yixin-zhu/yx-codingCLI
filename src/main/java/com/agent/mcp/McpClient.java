package com.agent.mcp;

import com.agent.mcp.jsonrpc.JsonRpcClient;
import com.agent.mcp.jsonrpc.JsonRpcException;
import com.agent.mcp.protocol.McpCallToolRequest;
import com.agent.mcp.protocol.McpCallToolResult;
import com.agent.mcp.protocol.McpInitializeRequest;
import com.agent.mcp.protocol.McpSchemaSanitizer;
import com.agent.mcp.protocol.McpToolDescriptor;
import com.agent.mcp.resources.McpResourceContent;
import com.agent.mcp.resources.McpResourceDescriptor;
import com.agent.mcp.transport.McpTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class McpClient implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_INITIALIZE_TIMEOUT_SECONDS = 60;
    private static final String INITIALIZE_TIMEOUT_PROPERTY = "agent.mcp.initialize.timeout.seconds";
    private static final String INITIALIZE_TIMEOUT_ENV = "AGENT_MCP_INITIALIZE_TIMEOUT_SECONDS";

    private final String serverName;
    private final JsonRpcClient rpc;
    private final McpTransport transport;
    private final Object callLock = new Object();
    private volatile JsonNode serverCapabilities = JsonNodeFactory.instance.objectNode();

    public McpClient(String serverName, McpTransport transport) {
        this.serverName = serverName;
        this.transport = transport;
        this.rpc = new JsonRpcClient(transport);
    }

    public void initialize() throws IOException {
        JsonNode result = rpc.request("initialize", McpInitializeRequest.toJson(), initializeTimeoutSeconds());
        serverCapabilities = result == null ? JsonNodeFactory.instance.objectNode() : result.path("capabilities");
        rpc.sendNotification("notifications/initialized", JsonNodeFactory.instance.objectNode());
    }

    static int initializeTimeoutSeconds() {
        String configured = System.getProperty(INITIALIZE_TIMEOUT_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(INITIALIZE_TIMEOUT_ENV);
        }
        if (configured == null || configured.isBlank()) {
            return DEFAULT_INITIALIZE_TIMEOUT_SECONDS;
        }
        try {
            int seconds = Integer.parseInt(configured.trim());
            return seconds > 0 ? seconds : DEFAULT_INITIALIZE_TIMEOUT_SECONDS;
        } catch (NumberFormatException ignored) {
            return DEFAULT_INITIALIZE_TIMEOUT_SECONDS;
        }
    }

    public boolean supportsResources() {
        return serverCapabilities.has("resources");
    }

    public List<McpToolDescriptor> listTools() throws IOException {
        synchronized (callLock) {
            JsonNode result = rpc.request("tools/list", JsonNodeFactory.instance.objectNode(), 30);
            JsonNode tools = result.path("tools");
            if (!tools.isArray()) {
                return List.of();
            }
            List<McpToolDescriptor> descriptors = new ArrayList<>();
            for (JsonNode tool : tools) {
                String name = tool.path("name").asText("");
                if (name.isBlank()) {
                    continue;
                }
                String description = tool.path("description").asText("");
                JsonNode schema = McpSchemaSanitizer.sanitize(tool.path("inputSchema"));
                descriptors.add(new McpToolDescriptor(
                        serverName,
                        name,
                        McpToolDescriptor.namespaced(serverName, name),
                        description,
                        schema
                ));
            }
            return descriptors;
        }
    }

    public String callTool(String toolName, String argumentsJson) throws IOException {
        synchronized (callLock) {
            JsonNode args;
            if (argumentsJson == null || argumentsJson.isBlank()) {
                args = JsonNodeFactory.instance.objectNode();
            } else {
                args = MAPPER.readTree(argumentsJson);
            }
            JsonNode result = rpc.request("tools/call", McpCallToolRequest.toJson(toolName, args), 60);
            McpCallToolResult callResult = MAPPER.treeToValue(result, McpCallToolResult.class);
            String text = callResult.formatForLlm();
            if (callResult.isError()) {
                return "MCP 工具返回错误: " + text;
            }
            return text;
        }
    }

    public List<McpResourceDescriptor> listResources() throws IOException {
        synchronized (callLock) {
            try {
                JsonNode result = rpc.request("resources/list", JsonNodeFactory.instance.objectNode(), 30);
                JsonNode resources = result.path("resources");
                if (!resources.isArray()) {
                    return List.of();
                }
                List<McpResourceDescriptor> descriptors = new ArrayList<>();
                for (JsonNode resource : resources) {
                    McpResourceDescriptor descriptor = McpResourceDescriptor.fromJson(serverName, resource);
                    if (descriptor != null) {
                        descriptors.add(descriptor);
                    }
                }
                return descriptors;
            } catch (JsonRpcException e) {
                if (e.code() == -32601) {
                    return List.of();
                }
                throw e;
            }
        }
    }

    public List<McpResourceContent> readResource(String uri) throws IOException {
        synchronized (callLock) {
            ObjectNode params = JsonNodeFactory.instance.objectNode();
            params.put("uri", uri);
            JsonNode result = rpc.request("resources/read", params, 60);
            JsonNode contents = result.path("contents");
            if (!contents.isArray()) {
                return List.of();
            }
            List<McpResourceContent> resourceContents = new ArrayList<>();
            for (JsonNode content : contents) {
                McpResourceContent resourceContent = McpResourceContent.fromJson(content);
                if (resourceContent != null) {
                    resourceContents.add(resourceContent);
                }
            }
            return resourceContents;
        }
    }

    public void onNotification(Consumer<JsonNode> listener) {
        rpc.onNotification(listener);
    }

    public static String formatResources(List<McpResourceDescriptor> resources) {
        if (resources == null || resources.isEmpty()) {
            return "📭 该 MCP server 暂无 resources";
        }
        StringBuilder sb = new StringBuilder("📚 MCP resources（").append(resources.size()).append("）\n");
        for (McpResourceDescriptor resource : resources) {
            sb.append("- ").append(resource.uri());
            String name = resource.displayName();
            if (name != null && !name.isBlank() && !name.equals(resource.uri())) {
                sb.append(" | ").append(name);
            }
            if (resource.mimeType() != null && !resource.mimeType().isBlank()) {
                sb.append(" | ").append(resource.mimeType());
            }
            if (resource.description() != null && !resource.description().isBlank()) {
                sb.append("\n  ").append(resource.description());
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    public static String formatResourceContents(List<McpResourceContent> contents) {
        if (contents == null || contents.isEmpty()) {
            return "📭 MCP resource 内容为空";
        }
        StringBuilder sb = new StringBuilder();
        for (McpResourceContent content : contents) {
            String mimeType = content.mimeType() == null || content.mimeType().isBlank()
                    ? "application/octet-stream"
                    : content.mimeType();
            sb.append("<resource uri=\"").append(escapeXml(content.uri()))
                    .append("\" mimeType=\"").append(escapeXml(mimeType)).append("\">\n");
            if (content.isText()) {
                sb.append(content.text());
            } else {
                sb.append("[binary resource blob omitted, base64 length=")
                        .append(content.blob() == null ? 0 : content.blob().length())
                        .append(']');
            }
            sb.append("\n</resource>\n");
        }
        return sb.toString().trim();
    }

    private static String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    public List<String> stderrLines() {
        return transport.stderrLines();
    }

    public Long processId() {
        return transport.processId();
    }

    public String transportName() {
        return transport.transportName();
    }

    @Override
    public void close() {
        rpc.close();
    }
}
