package com.agent.mcp.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class NotificationRouterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void dispatchesNotificationAsynchronously() throws Exception {
        NotificationRouter router = new NotificationRouter();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> uri = new AtomicReference<>();
        router.on("notifications/resources/updated", params -> {
            uri.set(params.path("uri").asText());
            latch.countDown();
        });

        router.accept(MAPPER.readTree("""
                {"jsonrpc":"2.0","method":"notifications/resources/updated","params":{"uri":"file://x"}}
                """));

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals("file://x", uri.get());
        router.close();
    }

    @Test
    void ignoresMessagesWithId() throws Exception {
        NotificationRouter router = new NotificationRouter();
        CountDownLatch latch = new CountDownLatch(1);
        router.on("notifications/tools/list_changed", ignored -> latch.countDown());

        router.accept(MAPPER.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list","result":{}}
                """));

        assertFalse(latch.await(200, TimeUnit.MILLISECONDS));
        router.close();
    }
}
