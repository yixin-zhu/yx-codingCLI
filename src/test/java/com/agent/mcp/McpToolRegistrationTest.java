package com.agent.mcp;

import com.agent.mcp.protocol.McpToolDescriptor;
import com.agent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class McpToolRegistrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void registersAndRoutesMcpToolToInvoker(@TempDir Path tempDir) throws Exception {
        ToolRegistry registry = new ToolRegistry(tempDir);
        McpToolDescriptor descriptor = sampleDescriptor();
        registry.registerMcpTool(descriptor, args -> "echo:" + args);

        assertTrue(registry.hasTool("mcp__demo__echo"));
        assertTrue(registry.getToolDefinitions().stream().anyMatch(t -> t.name().equals("mcp__demo__echo")));
        List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("call-1", "mcp__demo__echo", "{\"text\":\"hi\"}")));
        assertEquals("echo:{\"text\":\"hi\"}", results.get(0).result());
    }

    @Test
    void unregisterRemovesMcpToolFromBothViews(@TempDir Path tempDir) throws Exception {
        ToolRegistry registry = new ToolRegistry(tempDir);
        McpToolDescriptor descriptor = sampleDescriptor();
        registry.registerMcpTool(descriptor, args -> "echo:" + args);
        registry.unregisterMcpTool("mcp__demo__echo");

        assertFalse(registry.hasTool("mcp__demo__echo"));
        assertTrue(registry.getToolDefinitions().stream().noneMatch(t -> t.name().equals("mcp__demo__echo")));
    }

    @Test
    void invokerExceptionsAreReportedAsToolErrorWithoutCrashingRegistry(@TempDir Path tempDir) throws Exception {
        ToolRegistry registry = new ToolRegistry(tempDir);
        registry.registerMcpTool(sampleDescriptor(), args -> {
            throw new RuntimeException("upstream broke");
        });

        List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("call-1", "mcp__demo__echo", "{}")));
        assertTrue(results.get(0).result().contains("upstream broke"),
                "结果应包含 invoker 抛出的错误信息: " + results.get(0).result());
    }

    @Test
    void registerMcpToolRejectsNullArgs(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry(tempDir);
        assertThrows(NullPointerException.class,
                () -> registry.registerMcpTool(null, args -> "x"));
        assertThrows(NullPointerException.class,
                () -> registry.registerMcpTool(sampleDescriptor(), null));
    }

    @Test
    void replaceMcpToolsForServerAtomicallyReplacesOnlyThatServer(@TempDir Path tempDir) throws Exception {
        ToolRegistry registry = new ToolRegistry(tempDir);
        registry.registerMcpTool(sampleDescriptor("demo", "old"), args -> "old");
        registry.registerMcpTool(sampleDescriptor("other", "keep"), args -> "keep");

        registry.replaceMcpToolsForServer("demo",
                List.of(sampleDescriptor("demo", "new")),
                descriptor -> args -> "new:" + descriptor.name());

        assertFalse(registry.hasTool("mcp__demo__old"));
        assertTrue(registry.hasTool("mcp__demo__new"));
        assertTrue(registry.hasTool("mcp__other__keep"));
        List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("call-1", "mcp__demo__new", "{}")));
        assertEquals("new:new", results.get(0).result());
    }

    private static McpToolDescriptor sampleDescriptor() throws Exception {
        return sampleDescriptor("demo", "echo");
    }

    private static McpToolDescriptor sampleDescriptor(String server, String name) throws Exception {
        return new McpToolDescriptor(
                server,
                name,
                "mcp__" + server + "__" + name,
                "Echo input",
                MAPPER.readTree("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}")
        );
    }
}
