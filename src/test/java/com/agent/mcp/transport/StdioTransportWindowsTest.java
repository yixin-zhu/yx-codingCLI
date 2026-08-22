package com.agent.mcp.transport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class StdioTransportWindowsTest {

    @Test
    void canSpawnNpxOnWindows() throws Exception {
        StdioTransport transport = new StdioTransport(
                "npx", List.of("--version"), Map.of(), null);
        try {
            assertNotNull(transport.processId(), "npx 子进程应成功启动");
            assertEquals("stdio", transport.transportName());
        } finally {
            assertDoesNotThrow(transport::close);
        }
    }
}
