package com.agent.context;

import com.agent.llm.LlmClient;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextProfileTest {

    @Test
    void from128kWindowDerivesExpectedBudgets() {
        ContextProfile profile = ContextProfile.from(stubClient(128_000, false));

        assertEquals(128_000, profile.maxContextWindow());
        assertEquals(57_600, profile.shortTermMemoryBudget());
        assertEquals(640, profile.memoryContextTokens());
        assertTrue(profile.mcpResourceIndexEnabled());
        assertEquals(95_000, profile.compressionTriggerTokens());
        assertEquals(102_400, profile.agentTokenBudget());
    }

    @Test
    void from1mWindowRaisesCompressionThreshold() {
        ContextProfile profile = ContextProfile.from(stubClient(1_000_000, true));

        assertEquals(1_000_000, profile.maxContextWindow());
        assertEquals(450_000, profile.shortTermMemoryBudget());
        assertEquals(967_000, profile.compressionTriggerTokens());
        assertTrue(profile.promptCachingSupported());
        assertEquals("automatic", profile.promptCacheMode());
    }

    @Test
    void smallWindowDisablesMcpResourceIndex() {
        ContextProfile profile = ContextProfile.from(stubClient(16_000, false));
        assertFalse(profile.mcpResourceIndexEnabled());
    }

    @Test
    void customProfileOverridesShortTermBudget() {
        ContextProfile profile = ContextProfile.custom(64_000, 8_000);
        assertEquals(8_000, profile.shortTermMemoryBudget());
        assertEquals(64_000, profile.maxContextWindow());
    }

    @Test
    void summaryContainsKeyFields() {
        ContextProfile profile = ContextProfile.from(stubClient(128_000, true));
        String summary = profile.summary();
        assertTrue(summary.contains("window: 128000"));
        assertTrue(summary.contains("短期记忆预算"));
        assertTrue(summary.contains("prompt cache"));
    }

    private static LlmClient stubClient(int window, boolean caching) {
        return new LlmClient() {
            @Override
            public ChatResponse chat(List<Message> messages, List<Tool> tools) {
                return new ChatResponse("assistant", "ok", List.of(), 0, 0);
            }

            @Override
            public int maxContextWindow() {
                return window;
            }

            @Override
            public boolean supportsPromptCaching() {
                return caching;
            }

            @Override
            public String promptCacheMode() {
                return caching ? "automatic" : "none";
            }

            @Override
            public String getModelName() {
                return "test-model";
            }
        };
    }
}
