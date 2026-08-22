package com.agent.mcp.resources;

import com.agent.mcp.InMemoryTransport;
import com.agent.mcp.McpClient;
import com.agent.mcp.protocol.McpToolDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpResourceToolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void listResourcesInvokerFormatsOutput() throws Exception {
        InMemoryTransport transport = new InMemoryTransport()
                .handle("initialize", p -> readJson("""
                        {"capabilities":{"resources":{}}}
                        """))
                .handle("resources/list", p -> readJson("""
                        {"resources":[{"uri":"demo://doc","name":"doc","mimeType":"text/plain"}]}
                        """));
        McpClient client = new McpClient("demo", transport);
        client.initialize();

        McpToolDescriptor descriptor = McpResourceTool.descriptors("demo").get(0);
        String result = McpResourceTool.invoker(client, descriptor).apply("{}");

        assertTrue(result.contains("demo://doc"));
        assertTrue(result.contains("text/plain"));
        client.close();
    }

    private static com.fasterxml.jackson.databind.JsonNode readJson(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void readResourceInvokerRequiresUri() throws Exception {
        InMemoryTransport transport = new InMemoryTransport()
                .handle("initialize", p -> MAPPER.createObjectNode());
        McpClient client = new McpClient("demo", transport);
        client.initialize();

        McpToolDescriptor descriptor = McpResourceTool.descriptors("demo").get(1);
        String result = McpResourceTool.invoker(client, descriptor).apply("{}");

        assertTrue(result.contains("缺少必填参数 uri"));
        client.close();
    }
}
