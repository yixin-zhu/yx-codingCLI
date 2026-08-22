package com.agent.mcp.notifications;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 路由 MCP server 推来的 notification；handler 在独立线程执行，避免在 stdout reader 线程里同步 RPC 死锁。
 */
public class NotificationRouter implements Consumer<JsonNode>, AutoCloseable {
    private final Map<String, Consumer<JsonNode>> handlers = new ConcurrentHashMap<>();
    private final ExecutorService dispatcher;

    public NotificationRouter() {
        AtomicInteger threadId = new AtomicInteger();
        this.dispatcher = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "agent-mcp-notifications-" + threadId.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    public void on(String method, Consumer<JsonNode> handler) {
        if (method == null || method.isBlank() || handler == null) {
            return;
        }
        handlers.put(method, handler);
    }

    @Override
    public void accept(JsonNode message) {
        if (message == null || message.has("id")) {
            return;
        }
        String method = message.path("method").asText("");
        Consumer<JsonNode> handler = handlers.get(method);
        if (handler == null) {
            return;
        }
        JsonNode params = message.path("params");
        try {
            dispatcher.submit(() -> {
                try {
                    handler.accept(params);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    @Override
    public void close() {
        dispatcher.shutdownNow();
    }
}
