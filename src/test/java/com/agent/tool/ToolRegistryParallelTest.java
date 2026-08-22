package com.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryParallelTest {

    @Test
    void shouldExecuteToolsInParallelPreservingOrder(@TempDir Path tempDir) throws Exception {
        AtomicInteger current = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch bothStarted = new CountDownLatch(2);

        ToolRegistry registry = new ToolRegistry(tempDir) {
            @Override
            protected ToolExecutionResult doExecuteTool(ToolInvocation invocation) {
                int now = current.incrementAndGet();
                peak.updateAndGet(prev -> Math.max(prev, now));
                bothStarted.countDown();
                try {
                    assertTrue(bothStarted.await(5, TimeUnit.SECONDS), "工具调用应进入并行执行区");
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    current.decrementAndGet();
                }
                return new ToolExecutionResult(
                        invocation.id(), invocation.name(), "result-" + invocation.name(), 50);
            }
        };

        List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("call_1", "first", "{}"),
                new ToolRegistry.ToolInvocation("call_2", "second", "{}")
        ));

        assertEquals(2, peak.get(), "两个工具调用应并行执行");
        assertEquals("call_1", results.get(0).id());
        assertEquals("result-first", results.get(0).result());
        assertEquals("call_2", results.get(1).id());
        assertEquals("result-second", results.get(1).result());
    }

    @Test
    void shouldCompleteThreeSlowReadsFasterThanSerialSum(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry(tempDir) {
            @Override
            protected ToolExecutionResult doExecuteTool(ToolInvocation invocation) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new ToolExecutionResult(
                        invocation.id(), invocation.name(), "content-" + invocation.id(), 200);
            }
        };

        long started = System.nanoTime();
        List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("call_1", "read_file", "{\"path\":\"a.txt\"}"),
                new ToolRegistry.ToolInvocation("call_2", "read_file", "{\"path\":\"b.txt\"}"),
                new ToolRegistry.ToolInvocation("call_3", "read_file", "{\"path\":\"c.txt\"}")
        ));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertEquals(3, results.size());
        assertEquals("call_1", results.get(0).id());
        assertEquals("call_2", results.get(1).id());
        assertEquals("call_3", results.get(2).id());
        assertTrue(elapsedMs < 550, "并行耗时应明显小于串行之和(600ms)，实际: " + elapsedMs + "ms");
    }

    @Test
    void shouldReturnTimeoutWhenBatchLimitReached(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry(tempDir, 1) {
            @Override
            protected ToolExecutionResult doExecuteTool(ToolInvocation invocation) {
                if ("slow".equals(invocation.name())) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return new ToolExecutionResult(
                        invocation.id(), invocation.name(), "result-" + invocation.name(), 0);
            }
        };

        List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("call_1", "slow", "{}"),
                new ToolRegistry.ToolInvocation("call_2", "fast", "{}")
        ));

        assertTrue(results.get(0).timedOut());
        assertTrue(results.get(0).result().contains("工具执行超时"));
        assertEquals("result-fast", results.get(1).result());
    }
}
