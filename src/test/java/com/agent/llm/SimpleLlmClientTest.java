package com.agent.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleLlmClientTest {

    @Test
    void deepseekChatUses128kWindow() {
        SimpleLlmClient client = new SimpleLlmClient("https://api.deepseek.com", "key", "deepseek-chat");
        assertEquals(128_000, client.maxContextWindow());
        assertEquals("deepseek-chat", client.getModelName());
        assertTrue(client.supportsPromptCaching());
    }

    @Test
    void v4ModelUses1mWindow() {
        SimpleLlmClient client = new SimpleLlmClient("https://api.deepseek.com", "key", "deepseek-v4-flash");
        assertEquals(1_000_000, client.maxContextWindow());
    }
}
