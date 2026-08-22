package com.agent.mcp;

import com.agent.mcp.jsonrpc.JsonRpcClient;
import com.agent.mcp.protocol.McpCallToolRequest;
import com.agent.mcp.protocol.McpCallToolResult;
import com.agent.mcp.protocol.McpInitializeRequest;
import com.agent.mcp.protocol.McpSchemaSanitizer;
import com.agent.mcp.protocol.McpToolDescriptor;
import com.agent.mcp.transport.McpTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class McpClient implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_INITIALIZE_TIMEOUT_SECONDS = 60;
    private static final String INITIALIZE_TIMEOUT_PROPERTY = "agent.mcp.initialize.timeout.seconds";
    private static final String INITIALIZE_TIMEOUT_ENV = "AGENT_MCP_INITIALIZE_TIMEOUT_SECONDS";

    private final String serverName;
    private final JsonRpcClient rpc;
    private final McpTransport transport;
    private final Object callLock = new Object();

    public McpClient(String serverName, McpTransport transport) {
        this.serverName = serverName;
        this.transport = transport;
        this.rpc = new JsonRpcClient(transport);
    }

    public void initialize() throws IOException {
        rpc.request("initialize", McpInitializeRequest.toJson(), initializeTimeoutSeconds());
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

    public List<McpToolDescriptor> listTools() throws IOException {
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
