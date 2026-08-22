package com.agent.mcp;

import com.agent.mcp.protocol.McpToolDescriptor;
import com.agent.mcp.resources.McpResourceTool;
import com.agent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class McpResourceRegistrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void registersResourceVirtualTools(@TempDir Path tempDir) throws Exception {
        ToolRegistry registry = new ToolRegistry(tempDir);
        List<McpToolDescriptor> descriptors = List.of(
                new McpToolDescriptor("demo", "echo", "mcp__demo__echo", "echo",
                        MAPPER.readTree("{\"type\":\"object\"}"))
        );
        descriptors = new java.util.ArrayList<>(descriptors);
        descriptors.addAll(McpResourceTool.descriptors("demo"));

        registry.replaceMcpToolsForServer("demo", descriptors,
                descriptor -> args -> "ok:" + descriptor.name());

        assertTrue(registry.hasTool("mcp__demo__list_resources"));
        assertTrue(registry.hasTool("mcp__demo__read_resource"));
        assertTrue(registry.hasTool("mcp__demo__echo"));
    }
}
