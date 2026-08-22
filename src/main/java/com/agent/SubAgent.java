package com.agent;

import com.agent.llm.LlmClient;
import com.agent.project.AgentMdLoader;
import com.agent.prompt.PromptAssembler;
import com.agent.prompt.PromptContext;
import com.agent.prompt.PromptMode;
import com.agent.tool.ToolRegistry;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 带角色的轻量子代理，共享 LLM 与 ToolRegistry，独立对话历史。
 */
public class SubAgent {

    private static final Logger log = LoggerFactory.getLogger(SubAgent.class);

    private final String name;
    private final AgentRole role;
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final PromptAssembler promptAssembler;
    private final List<LlmClient.Message> conversationHistory;

    public SubAgent(String name, AgentRole role, LlmClient llmClient, ToolRegistry toolRegistry) {
        this(name, role, llmClient, toolRegistry, PromptAssembler.createDefault());
    }

    SubAgent(String name, AgentRole role, LlmClient llmClient, ToolRegistry toolRegistry,
             PromptAssembler promptAssembler) {
        this.name = Objects.requireNonNull(name);
        this.role = Objects.requireNonNull(role);
        this.llmClient = Objects.requireNonNull(llmClient);
        this.toolRegistry = Objects.requireNonNull(toolRegistry);
        this.promptAssembler = promptAssembler != null ? promptAssembler : PromptAssembler.createDefault();
        this.conversationHistory = new ArrayList<>();
        this.conversationHistory.add(LlmClient.Message.system(buildSystemPrompt()));
    }

    public String getName() {
        return name;
    }

    public AgentRole getRole() {
        return role;
    }

    public AgentMessage execute(AgentMessage task) {
        return execute(task, System.out);
    }

    public AgentMessage execute(AgentMessage task, PrintStream out) {
        log.info("[{}] executing task from {}: type={}", name, task.fromAgent(), task.type());
        conversationHistory.add(LlmClient.Message.user(task.content()));

        AgentBudget budget = role == AgentRole.WORKER
                ? new AgentBudget(5, 20)
                : new AgentBudget(2, 6);

        while (true) {
            AgentBudget.ExitReason exitReason = budget.check();
            if (exitReason != AgentBudget.ExitReason.WITHIN_BUDGET) {
                String description = budget.describeExit(exitReason);
                log.warn("[{}] budget exhausted: {}", name, description);
                return AgentMessage.error(name, role, description);
            }

            budget.beginIteration();
            try {
                List<LlmClient.Tool> tools = shouldUseTools() ? toolRegistry.getToolDefinitions() : List.of();
                LlmClient.ChatResponse response = llmClient.chat(conversationHistory, tools);
                budget.recordTokens(response.inputTokens(), response.outputTokens());

                if (response.hasToolCalls()) {
                    budget.recordToolCalls(response.toolCalls());
                    conversationHistory.add(LlmClient.Message.assistant(response.content(), response.toolCalls()));
                    printToolCalls(out, response.toolCalls());

                    List<ToolRegistry.ToolInvocation> invocations = response.toolCalls().stream()
                            .map(tc -> new ToolRegistry.ToolInvocation(tc.id(), tc.name(), tc.arguments()))
                            .toList();
                    List<ToolRegistry.ToolExecutionResult> results = toolRegistry.executeTools(invocations);
                    for (ToolRegistry.ToolExecutionResult result : results) {
                        conversationHistory.add(LlmClient.Message.tool(result.id(), result.result()));
                    }
                    continue;
                }

                conversationHistory.add(LlmClient.Message.assistant(response.content()));
                return AgentMessage.result(name, role, response.content());
            } catch (Exception e) {
                log.error("[{}] LLM call failed", name, e);
                return AgentMessage.error(name, role, "LLM 调用失败: " + e.getMessage());
            }
        }
    }

    public AgentMessage executeWithContext(AgentMessage task, String context) {
        return executeWithContext(task, context, System.out);
    }

    public AgentMessage executeWithContext(AgentMessage task, String context, PrintStream out) {
        String enrichedContent = task.content();
        if (context != null && !context.isBlank()) {
            enrichedContent = context + "\n\n当前任务：" + task.content();
        }
        AgentMessage enrichedTask = new AgentMessage(task.fromAgent(), task.fromRole(), enrichedContent, task.type());
        return execute(enrichedTask, out);
    }

    public AgentMessage review(String originalTask, String executionResult) {
        return review(originalTask, executionResult, System.out);
    }

    public AgentMessage review(String originalTask, String executionResult, PrintStream out) {
        String reviewInput = "原始任务：" + originalTask + "\n\n执行结果：\n" + executionResult;
        return execute(AgentMessage.task("orchestrator", reviewInput), out);
    }

    public void clearHistory() {
        LlmClient.Message systemMsg = conversationHistory.get(0);
        conversationHistory.clear();
        conversationHistory.add(systemMsg);
    }

    boolean shouldUseTools() {
        return role == AgentRole.WORKER;
    }

    private String buildSystemPrompt() {
        return promptAssembler.assemble(
                promptMode(),
                PromptContext.builder()
                        .workspacePath(toolRegistry.getProjectPath())
                        .projectMemoryContext(AgentMdLoader.loadForPrompt(Path.of(toolRegistry.getProjectPath())))
                        .build()
        );
    }

    private PromptMode promptMode() {
        return switch (role) {
            case PLANNER -> PromptMode.TEAM_PLANNER;
            case WORKER -> PromptMode.TEAM_WORKER;
            case REVIEWER -> PromptMode.TEAM_REVIEWER;
        };
    }

    private static void printToolCalls(PrintStream out, List<LlmClient.ToolCall> toolCalls) {
        if (out == null || toolCalls == null) {
            return;
        }
        for (LlmClient.ToolCall toolCall : toolCalls) {
            out.printf("  🔧 调用工具: %s%n", toolCall.name());
        }
    }
}
