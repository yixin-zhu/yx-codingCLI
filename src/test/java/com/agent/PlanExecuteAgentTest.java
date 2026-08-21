package com.agent;

import com.agent.llm.LlmClient;
import com.agent.plan.ExecutionPlan;
import com.agent.plan.Planner;
import com.agent.plan.Task;
import com.agent.tool.ToolRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanExecuteAgentTest {

    @TempDir
    Path tempDir;

    @Test
    void cancelReviewStopsExecution() throws Exception {
        PlanExecuteAgent agent = new PlanExecuteAgent(
                new ScriptedLlmClient(new LinkedBlockingQueue<>()),
                new ToolRegistry(tempDir),
                new StubPlanner(),
                null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.cancel(),
                null
        );

        String result = agent.run("列出当前目录的文件");
        assertEquals("⏹️ 已取消本次计划执行。", result);
    }

    @Test
    void executesSingleTaskWithToolCall() throws Exception {
        Files.writeString(tempDir.resolve("sample.txt"), "plan-content");

        Queue<LlmClient.ChatResponse> responses = new LinkedBlockingQueue<>();
        responses.add(new LlmClient.ChatResponse(
                "assistant",
                "",
                List.of(new LlmClient.ToolCall("call_1", "read_file", "{\"path\":\"sample.txt\"}")),
                0,
                0
        ));
        responses.add(new LlmClient.ChatResponse("assistant", "已读取 sample.txt", List.of(), 0, 0));

        PlanExecuteAgent agent = new PlanExecuteAgent(
                new ScriptedLlmClient(responses),
                new ToolRegistry(tempDir),
                new StubPlanner(),
                null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                null
        );

        String result = agent.run("读取 sample.txt");

        assertTrue(result.contains("计划执行完成"));
        assertTrue(result.contains("已读取 sample.txt") || result.contains("plan-content"));
    }

    private static final class StubPlanner extends Planner {
        private StubPlanner() {
            super(new ScriptedLlmClient(new LinkedBlockingQueue<>()));
        }

        @Override
        public ExecutionPlan createPlan(String goal) {
            ExecutionPlan plan = new ExecutionPlan("plan-test", goal);
            plan.setSummary("test plan");
            plan.addTask(new Task("task_1", goal, Task.TaskType.FILE_READ));
            plan.computeExecutionOrder();
            return plan;
        }
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
                throw new IllegalStateException("缺少预设 LLM 响应");
            }
            return next;
        }
    }
}
