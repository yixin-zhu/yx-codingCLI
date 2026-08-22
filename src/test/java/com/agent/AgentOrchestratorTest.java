package com.agent;

import com.agent.llm.LlmClient;
import com.agent.memory.LongTermMemory;
import com.agent.memory.MemoryManager;
import com.agent.tool.ToolRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentOrchestratorTest {

    @Test
    void shouldParseSimplePlan() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(stubClient(), new ToolRegistry());
        String planJson = """
                {
                    "summary": "读取文件",
                    "steps": [
                        {
                            "id": "step_1",
                            "description": "读取 pom.xml",
                            "type": "FILE_READ",
                            "dependencies": []
                        }
                    ]
                }
                """;

        List<AgentOrchestrator.ExecutionStep> steps = orchestrator.parsePlan(planJson);
        assertEquals(1, steps.size());
        assertEquals("step_1", steps.get(0).id());
        assertEquals("读取 pom.xml", steps.get(0).description());
    }

    @Test
    void shouldParseMultiStepPlanWithDependencies() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(stubClient(), new ToolRegistry());
        String planJson = """
                {
                    "summary": "创建并验证项目",
                    "steps": [
                        {"id": "s1", "description": "创建项目", "type": "COMMAND", "dependencies": []},
                        {"id": "s2", "description": "读取 pom.xml", "type": "FILE_READ", "dependencies": ["s1"]},
                        {"id": "s3", "description": "验证结构", "type": "VERIFICATION", "dependencies": ["s2"]}
                    ]
                }
                """;

        List<AgentOrchestrator.ExecutionStep> steps = orchestrator.parsePlan(planJson);
        assertEquals(3, steps.size());
        assertEquals("step_1", steps.get(0).id());
        assertEquals("step_2", steps.get(1).id());
        assertEquals("step_3", steps.get(2).id());
        assertTrue(steps.get(0).dependencies().isEmpty());
        assertEquals(List.of("step_1"), steps.get(1).dependencies());
        assertEquals(List.of("step_2"), steps.get(2).dependencies());
    }

    @Test
    void shouldParsePlanWithTasksField() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(stubClient(), new ToolRegistry());
        String planJson = """
                {
                    "summary": "用 tasks 字段",
                    "tasks": [
                        {"id": "task_1", "description": "第一步", "type": "COMMAND", "dependencies": []}
                    ]
                }
                """;

        List<AgentOrchestrator.ExecutionStep> steps = orchestrator.parsePlan(planJson);
        assertEquals(1, steps.size());
        assertEquals("第一步", steps.get(0).description());
    }

    @Test
    void shouldReturnEmptyListForInvalidJson() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(stubClient(), new ToolRegistry());
        assertTrue(orchestrator.parsePlan("").isEmpty());
        assertTrue(orchestrator.parsePlan("not json").isEmpty());
        assertTrue(orchestrator.parsePlan("{}").isEmpty());
    }

    @Test
    void shouldGetExecutableSteps() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(stubClient(), new ToolRegistry());
        List<AgentOrchestrator.ExecutionStep> steps = new ArrayList<>(List.of(
                AgentOrchestrator.ExecutionStep.pending("step_1", "创建项目", "COMMAND", List.of()),
                AgentOrchestrator.ExecutionStep.pending("step_2", "验证结构", "VERIFICATION", List.of("step_1"))
        ));

        assertEquals(1, orchestrator.getExecutableSteps(steps).size());
        assertEquals("step_1", orchestrator.getExecutableSteps(steps).get(0).id());

        steps.set(0, steps.get(0).withResult("项目已创建"));
        assertEquals("step_2", orchestrator.getExecutableSteps(steps).get(0).id());
    }

    @Test
    void shouldParseReviewApproval() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(stubClient(), new ToolRegistry());
        assertTrue(orchestrator.parseReviewApproval("{\"approved\": true, \"summary\": \"通过\", \"issues\": []}"));
        assertFalse(orchestrator.parseReviewApproval("{\"approved\": false, \"summary\": \"未通过\", \"issues\": [\"x\"]}"));
        assertFalse(orchestrator.parseReviewApproval(null));
        assertFalse(orchestrator.parseReviewApproval(""));
        assertTrue(orchestrator.parseReviewApproval("审查通过，代码质量良好"));
        assertFalse(orchestrator.parseReviewApproval("hmm"));
    }

    @Test
    void shouldParseReviewIssues() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(stubClient(), new ToolRegistry());
        String reviewJson = """
                {
                    "approved": false,
                    "summary": "存在问题",
                    "issues": ["缺少错误处理", "代码风格不一致"]
                }
                """;
        String issues = orchestrator.parseReviewIssues(reviewJson);
        assertTrue(issues.contains("缺少错误处理"));
        assertTrue(issues.contains("代码风格不一致"));
    }

    @Test
    void shouldRetryRejectedStepUntilApproval(@TempDir Path tempDir) {
        Queue<LlmClient.ChatResponse> responses = new ArrayDeque<>(List.of(
                response("""
                        {
                          "summary": "单步任务",
                          "steps": [
                            {"id": "s1", "description": "执行任务", "type": "COMMAND", "dependencies": []}
                          ]
                        }
                        """),
                response("第一次执行结果"),
                response("{\"approved\": false, \"summary\": \"第一次未通过\", \"issues\": [\"需要补充细节\"]}"),
                response("第二次执行结果"),
                response("{\"approved\": false, \"summary\": \"第二次未通过\", \"issues\": [\"还缺最后结论\"]}"),
                response("第三次执行结果"),
                response("{\"approved\": true, \"summary\": \"通过\", \"issues\": []}")
        ));

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                new ScriptedLlmClient(responses),
                new ToolRegistry(tempDir),
                new MemoryManager(new ScriptedLlmClient(new ArrayDeque<>()), 32768,
                        com.agent.memory.TokenBudget.defaults(), new LongTermMemory(tempDir.toFile())),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)
        );

        String finalResult = orchestrator.run("测试重试逻辑");
        assertTrue(finalResult.contains("第三次执行结果"));
        assertFalse(finalResult.contains("第二次执行结果"));
    }

    @Test
    void shouldReportIncompleteRunWhenFailureBlocksRemainingSteps(@TempDir Path tempDir) {
        Queue<LlmClient.ChatResponse> responses = new ArrayDeque<>(List.of(
                response("""
                        {
                          "summary": "两步任务",
                          "steps": [
                            {"id": "s1", "description": "第一步", "type": "COMMAND", "dependencies": []},
                            {"id": "s2", "description": "第二步", "type": "ANALYSIS", "dependencies": ["s1"]}
                          ]
                        }
                        """),
                response("")
        ));

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                new ScriptedLlmClient(responses),
                new ToolRegistry(tempDir),
                new MemoryManager(new ScriptedLlmClient(new ArrayDeque<>()), 32768,
                        com.agent.memory.TokenBudget.defaults(), new LongTermMemory(tempDir.toFile())),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)
        );

        String finalResult = orchestrator.run("测试失败阻塞");
        assertTrue(finalResult.contains("未完全完成"));
        assertTrue(finalResult.contains("[step_1] ❌ 第一步"));
        assertTrue(finalResult.contains("[step_2] ⏳ 第二步"));
    }

    private static LlmClient stubClient() {
        return (messages, tools) -> new LlmClient.ChatResponse("assistant", "ok", null, 0, 0);
    }

    private static LlmClient.ChatResponse response(String content) {
        return new LlmClient.ChatResponse("assistant", content, null, 0, 0);
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
