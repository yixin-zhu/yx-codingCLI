package com.agent.memory;

import com.agent.llm.LlmClient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldCompressBeforeShortTermMemoryEvictsOldEntries() {
        StubLlmClient llmClient = new StubLlmClient(new ArrayDeque<>(List.of(
                new LlmClient.ChatResponse("assistant", "压缩摘要", List.of(), 100, 20)
        )));
        MemoryManager memoryManager = new MemoryManager(
                llmClient,
                40,
                TokenBudget.defaults(),
                new LongTermMemory(tempDir.toFile())
        );
        String longMessage = "a".repeat(36);

        memoryManager.addUserMessage(longMessage);
        memoryManager.addAssistantMessage(longMessage);
        memoryManager.addUserMessage(longMessage);
        memoryManager.addAssistantMessage(longMessage);

        assertTrue(memoryManager.getShortTermMemory().getAll().stream()
                .anyMatch(entry -> entry.getType() == MemoryEntry.MemoryType.SUMMARY));
    }

    @Test
    void shouldClearLongTermMemoryOnlyWhenExplicitlyRequested() {
        LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
        MemoryManager memoryManager = new MemoryManager(new StubLlmClient(new ArrayDeque<>()), 32768, TokenBudget.defaults(), longTermMemory);

        memoryManager.storeFact("用户偏好使用中文交流");
        memoryManager.storeFact("项目路径: /tmp/demo");
        assertEquals(2, longTermMemory.size());

        memoryManager.clearLongTerm();
        assertEquals(0, longTermMemory.size());
    }

    @Test
    void shouldStoreProjectScopedFactsByDefault() {
        LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
        MemoryManager memoryManager = new MemoryManager(new StubLlmClient(new ArrayDeque<>()), 32768, TokenBudget.defaults(), longTermMemory);
        memoryManager.setProjectPath("/repo/current");

        memoryManager.storeFact("当前项目使用 Java 17");
        memoryManager.storeFact("默认用中文回答", "global");

        MemoryEntry projectEntry = longTermMemory.search("Java", 5, memoryManager.getCurrentProject()).get(0);
        assertEquals("project", projectEntry.getMetadata().get("scope"));
        assertEquals(memoryManager.getCurrentProject(), projectEntry.getMetadata().get("project"));
        assertEquals("global", longTermMemory.search("中文", 5).get(0).getMetadata().get("scope"));
    }

    @Test
    void shouldSearchOnlyCurrentProjectAndGlobalFacts() {
        LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
        MemoryManager memoryManager = new MemoryManager(new StubLlmClient(new ArrayDeque<>()), 32768, TokenBudget.defaults(), longTermMemory);
        memoryManager.setProjectPath("/repo/current");
        longTermMemory.store(new MemoryEntry("current", "当前项目使用 Java 17", MemoryEntry.MemoryType.FACT,
                java.util.Map.of("scope", "project", "project", memoryManager.getCurrentProject()), 10));
        longTermMemory.store(new MemoryEntry("other", "其他项目使用 Java 8", MemoryEntry.MemoryType.FACT,
                java.util.Map.of("scope", "project", "project", "/repo/other"), 10));

        List<MemoryEntry> results = memoryManager.searchLongTerm("Java", 10);
        assertEquals(1, results.size());
        assertEquals("current", results.get(0).getId());
    }

    @Test
    void shouldBuildMemoryContextForQuery() {
        LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
        MemoryManager memoryManager = new MemoryManager(new StubLlmClient(new ArrayDeque<>()), 32768, TokenBudget.defaults(), longTermMemory);
        memoryManager.setProjectPath("/repo/current");
        memoryManager.storeFact("本项目使用 Java 17");

        String context = memoryManager.buildContextForQuery("这个项目用什么 Java 版本", 2048);
        assertTrue(context.contains("Java 17"));
        assertTrue(context.contains("相关长期记忆"));
    }

    private static final class StubLlmClient implements LlmClient {
        private final Queue<ChatResponse> responses;

        private StubLlmClient(Queue<ChatResponse> responses) {
            this.responses = responses;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            ChatResponse response = responses.poll();
            if (response == null) {
                return new ChatResponse("assistant", "默认摘要", List.of(), 0, 0);
            }
            return response;
        }
    }
}
