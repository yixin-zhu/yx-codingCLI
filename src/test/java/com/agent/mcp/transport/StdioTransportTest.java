package com.agent.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisabledOnOs(OS.WINDOWS)
class StdioTransportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void echoesJsonMessageBackToListener() throws Exception {
        StdioTransport transport = new StdioTransport(
                "sh", List.of("-c", "while IFS= read -r line; do echo \"$line\"; done"),
                Map.of(), null);
        try {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<JsonNode> messageHolder = new AtomicReference<>();
            transport.onReceive(node -> {
                messageHolder.set(node);
                received.countDown();
            });

            JsonNode payload = MAPPER.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");
            transport.send(payload);

            assertTrue(received.await(2, TimeUnit.SECONDS), "应能在 2 秒内收到回显");
            assertEquals("ping", messageHolder.get().path("method").asText());
            assertEquals("stdio", transport.transportName());
            assertNotNull(transport.processId());
        } finally {
            transport.close();
        }
    }

    @Test
    void stderrLinesAreCapturedWithoutBlockingStdout() throws Exception {
        String script = "for i in $(seq 1 50); do echo error-$i 1>&2; done; echo '{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"ok\"}'";
        StdioTransport transport = new StdioTransport(
                "sh", List.of("-c", script), Map.of(), null);
        try {
            CountDownLatch received = new CountDownLatch(1);
            transport.onReceive(node -> {
                if ("ok".equals(node.path("method").asText())) {
                    received.countDown();
                }
            });
            assertTrue(received.await(3, TimeUnit.SECONDS),
                    "stderr 大量输出不应阻塞 stdout 路径");
            Thread.sleep(200);
            List<String> lines = transport.stderrLines();
            assertFalse(lines.isEmpty());
            assertTrue(lines.size() <= 200, "stderr 环形 buffer 不能超过 200 行");
        } finally {
            transport.close();
        }
    }

    @Test
    void closeTerminatesLongRunningProcess() throws Exception {
        StdioTransport transport = new StdioTransport(
                "sh", List.of("-c", "sleep 30"), Map.of(), null);
        Long pid = transport.processId();
        assertNotNull(pid);

        long start = System.currentTimeMillis();
        transport.close();
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 5000, "close 应在 5 秒内完成（实际 " + elapsed + "ms）");
    }

    @Test
    void sendAfterCloseFailsExplicitly() throws Exception {
        StdioTransport transport = new StdioTransport(
                "sh", List.of("-c", "cat"), Map.of(), null);
        transport.close();

        IOException ex = assertThrows(IOException.class,
                () -> transport.send(MAPPER.readTree("{}")));
        assertTrue(ex.getMessage().contains("closed"), "错误消息应明示已关闭: " + ex.getMessage());
    }
}
