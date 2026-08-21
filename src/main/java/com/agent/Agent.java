package com.agent;

import com.agent.llm.LlmClient;
import com.agent.prompt.PromptAssembler;
import com.agent.prompt.PromptContext;
import com.agent.prompt.PromptMode;
import com.agent.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent - 基于 ReAct 模式的智能体核心
 */
public class Agent {

    private static final Logger log = LoggerFactory.getLogger(Agent.class);

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final PromptAssembler promptAssembler;
    private final List<LlmClient.Message> conversationHistory;
    private String systemPrompt;

    public Agent(LlmClient llmClient, ToolRegistry toolRegistry) {
        this(llmClient, toolRegistry, null, PromptAssembler.createDefault());
    }

    public Agent(LlmClient llmClient, ToolRegistry toolRegistry, String customSystemPrompt) {
        this(llmClient, toolRegistry, customSystemPrompt, PromptAssembler.createDefault());
    }

    Agent(LlmClient llmClient, ToolRegistry toolRegistry, String customSystemPrompt, PromptAssembler promptAssembler) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.promptAssembler = promptAssembler;
        this.conversationHistory = new ArrayList<>();

        if (customSystemPrompt != null && !customSystemPrompt.isBlank()) {
            this.systemPrompt = customSystemPrompt;
        } else {
            this.systemPrompt = assembleSystemPrompt(toolRegistry.getProjectPath());
        }
        this.conversationHistory.add(LlmClient.Message.system(systemPrompt));
    }

    private String assembleSystemPrompt(String workspacePath) {
        return promptAssembler.assemble(
                PromptMode.AGENT,
                PromptContext.builder().workspacePath(workspacePath).build()
        );
    }

    public String run(String userInput) {
        log.info("用户输入: {}", userInput);
        conversationHistory.add(LlmClient.Message.user(userInput));

        AgentBudget budget = AgentBudget.defaults();

        while (true) {
            AgentBudget.ExitReason exitReason = budget.check();
            if (exitReason != AgentBudget.ExitReason.WITHIN_BUDGET) {
                log.warn("ReAct 循环触发兜底: {}", exitReason);
                return "❌ " + budget.describeExit(exitReason);
            }

            int iteration = budget.beginIteration();
            log.debug("ReAct 迭代 #{}", iteration);

            try {
                LlmClient.ChatResponse response = llmClient.chat(
                        conversationHistory,
                        toolRegistry.getToolDefinitions()
                );
                budget.recordTokens(response.inputTokens(), response.outputTokens());

                conversationHistory.add(LlmClient.Message.assistant(
                        response.content(),
                        response.toolCalls()
                ));

                if (!response.hasToolCalls()) {
                    log.info("Agent 完成，返回最终回复");
                    return response.content() == null ? "" : response.content();
                }

                budget.recordToolCalls(response.toolCalls());

                List<ToolRegistry.ToolInvocation> invocations = response.toolCalls().stream()
                        .map(tc -> new ToolRegistry.ToolInvocation(tc.id(), tc.name(), tc.arguments()))
                        .toList();

                List<ToolRegistry.ToolExecutionResult> results = toolRegistry.executeTools(invocations);
                for (ToolRegistry.ToolExecutionResult result : results) {
                    conversationHistory.add(LlmClient.Message.tool(result.id(), result.result()));
                    log.debug("工具 {} 执行完成", result.name());
                }
            } catch (Exception e) {
                log.error("ReAct 循环异常", e);
                String errorMsg = "执行过程中出现错误: " + e.getMessage();
                conversationHistory.add(LlmClient.Message.assistant(errorMsg, List.of()));
                return errorMsg;
            }
        }
    }

    public void reset() {
        conversationHistory.clear();
        this.systemPrompt = assembleSystemPrompt(toolRegistry.getProjectPath());
        conversationHistory.add(LlmClient.Message.system(systemPrompt));
        log.info("对话历史已重置");
    }

    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    public List<LlmClient.Message> getConversationHistory() {
        return List.copyOf(conversationHistory);
    }

    public List<String> getAvailableTools() {
        return toolRegistry.getToolNames().stream().toList();
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }
}
