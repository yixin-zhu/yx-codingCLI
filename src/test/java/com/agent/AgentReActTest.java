package com.agent;

import com.agent.llm.LlmClient;
import com.agent.tool.ToolRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentReActTest {

    @TempDir
    Path tempDir;

    @Test
    void reactLoopExecutesToolThenReturnsFinalAnswer() throws IOException {
        Files.writeString(tempDir.resolve("a.txt"), "hello");

        Queue<LlmClient.ChatResponse> scripted = new LinkedBlockingQueue<>();
        scripted.add(new LlmClient.ChatResponse(
                "assistant",
                "",
                List.of(new LlmClient.ToolCall("call_1", "read_file", "{\"path\":\"a.txt\"}")),
                0,
                0
        ));
        scripted.add(new LlmClient.ChatResponse("assistant", "文件内容是 hello", List.of(), 0, 0));

        LlmClient llm = new ScriptedLlmClient(scripted);
        Agent agent = new Agent(llm, new ToolRegistry(tempDir));

        String result = agent.run("读取 a.txt");

        assertEquals("文件内容是 hello", result);
        assertTrue(agent.getConversationHistory().stream().anyMatch(m -> "tool".equals(m.role())));
    }

    @Test
    void stagnationStopsRepeatedToolCalls() {
        List<LlmClient.ToolCall> sameCall = List.of(
                new LlmClient.ToolCall("call_1", "read_file", "{\"path\":\"missing.txt\"}")
        );
        List<LlmClient.ChatResponse> repeated = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            repeated.add(new LlmClient.ChatResponse("assistant", "", sameCall, 0, 0));
        }

        LlmClient llm = new ScriptedLlmClient(new LinkedBlockingQueue<>(repeated));
        Agent agent = new Agent(llm, new ToolRegistry(tempDir));

        String result = agent.run("一直读 missing.txt");

        assertTrue(result.startsWith("❌"));
        assertTrue(result.contains("重复"));
    }

    private static final class ScriptedLlmClient implements LlmClient {
        private final Queue<ChatResponse> responses;

        private ScriptedLlmClient(Queue<ChatResponse> responses) {
            this.responses = responses;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            ChatResponse next = responses.poll();
            if (next == null) {
                throw new IllegalStateException("没有更多预设 LLM 响应");
            }
            return next;
        }
    }
}
