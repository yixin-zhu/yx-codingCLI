package com.agent.plan;

import com.agent.llm.LlmClient;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerTest {

    @Test
    void parsePlanBuildsDependencyGraph() throws Exception {
        Planner planner = new Planner(new StubLlmClient("""
                {
                  "summary": "demo plan",
                  "tasks": [
                    {"id": "task_1", "description": "create project", "type": "COMMAND", "dependencies": []},
                    {"id": "task_2", "description": "read pom", "type": "FILE_READ", "dependencies": ["task_1"]}
                  ]
                }
                """), new PrintStream(new ByteArrayOutputStream()));

        ExecutionPlan plan = planner.parsePlan("demo goal", """
                ```json
                {
                  "summary": "demo plan",
                  "tasks": [
                    {"id": "task_1", "description": "create project", "type": "COMMAND", "dependencies": []},
                    {"id": "task_2", "description": "read pom", "type": "FILE_READ", "dependencies": ["task_1"]}
                  ]
                }
                ```
                """);

        assertEquals("demo plan", plan.getSummary());
        assertEquals(List.of("task_1", "task_2"), plan.getExecutionOrder());
        assertTrue(plan.getTask("task_1").getDependents().contains("task_2"));
    }

    @Test
    void simpleGoalCreatesMinimalPlan() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Planner planner = new Planner(new StubLlmClient("unused"), new PrintStream(output));

        ExecutionPlan plan = planner.createPlan("列出当前目录");

        assertEquals(1, plan.getAllTasks().size());
        assertEquals("task_1", plan.getTask("task_1").getId());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("正在规划任务"));
    }

    private static final class StubLlmClient implements LlmClient {
        private final String content;

        private StubLlmClient(String content) {
            this.content = content;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            return new ChatResponse("assistant", content, List.of(), 0, 0);
        }
    }
}
