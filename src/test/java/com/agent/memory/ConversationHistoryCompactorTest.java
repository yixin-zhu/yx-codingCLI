package com.agent.memory;

import com.agent.llm.LlmClient;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationHistoryCompactorTest {

    @Test
    void doesNothingWhenBelowTrigger() {
        StubCompactor compactor = new StubCompactor("MOCK SUMMARY", 3);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("system"));
        history.add(LlmClient.Message.user("hi"));

        assertFalse(compactor.compactIfNeeded(history, 100_000));
        assertEquals(2, history.size());
        assertEquals(0, compactor.summarizeCalls.get());
    }

    @Test
    void compactsOldRoundsAndKeepsRecentTurns() {
        StubCompactor compactor = new StubCompactor("MOCK SUMMARY OF OLD CONTENT", 2);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("SYSTEM_PROMPT"));
        for (int i = 0; i < 6; i++) {
            history.add(LlmClient.Message.user("Q" + i + ": " + longText(5_000)));
            history.add(LlmClient.Message.assistant("A" + i + ": " + longText(5_000)));
        }

        assertTrue(compactor.compactIfNeeded(history, 100));
        assertEquals(1, compactor.summarizeCalls.get());
        assertEquals(7, history.size());
        assertTrue(history.get(1).content().contains("已压缩的历史对话摘要"));
        assertTrue(history.get(3).content().startsWith("Q4"));
        assertTrue(history.get(5).content().startsWith("Q5"));
    }

    private static String longText(int size) {
        return "x".repeat(size);
    }

    private static class StubCompactor extends ConversationHistoryCompactor {
        private final AtomicInteger summarizeCalls = new AtomicInteger();
        private final String summary;

        private StubCompactor(String summary, int retainRecentRounds) {
            super(null, retainRecentRounds);
            this.summary = summary;
        }

        @Override
        protected String summarize(List<LlmClient.Message> messages) {
            summarizeCalls.incrementAndGet();
            return summary;
        }
    }
}
