package com.agent;

import com.agent.llm.LlmClient;
import com.agent.memory.ConversationHistoryCompactor;
import com.agent.memory.MemoryManager;
import com.agent.project.AgentMdLoader;
import com.agent.prompt.PromptAssembler;
import com.agent.prompt.PromptContext;
import com.agent.prompt.PromptMode;
import com.agent.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Agent {

    private static final Logger log = LoggerFactory.getLogger(Agent.class);

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final PromptAssembler promptAssembler;
    private final MemoryManager memoryManager;
    private final ConversationHistoryCompactor historyCompactor;
    private final List<LlmClient.Message> conversationHistory;
    private String systemPrompt;

    public Agent(LlmClient llmClient, ToolRegistry toolRegistry) {
        this(llmClient, toolRegistry, null, PromptAssembler.createDefault(), new MemoryManager(llmClient));
    }

    public Agent(LlmClient llmClient, ToolRegistry toolRegistry, String customSystemPrompt) {
        this(llmClient, toolRegistry, customSystemPrompt, PromptAssembler.createDefault(), new MemoryManager(llmClient));
    }

    Agent(LlmClient llmClient, ToolRegistry toolRegistry, String customSystemPrompt,
          PromptAssembler promptAssembler, MemoryManager memoryManager) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.promptAssembler = promptAssembler;
        this.memoryManager = memoryManager;
        this.historyCompactor = new ConversationHistoryCompactor(llmClient);
        this.conversationHistory = new ArrayList<>();

        memoryManager.setProjectPath(toolRegistry.getProjectPath());
        toolRegistry.setMemorySaver(memoryManager::storeFact);

        if (customSystemPrompt != null && !customSystemPrompt.isBlank()) {
            this.systemPrompt = customSystemPrompt;
        } else {
            this.systemPrompt = assembleSystemPrompt(toolRegistry.getProjectPath(), "");
        }
        this.conversationHistory.add(LlmClient.Message.system(systemPrompt));
    }

    public String run(String userInput) {
        log.info("用户输入: {}", userInput);
        memoryManager.addUserMessage(userInput);
        updateSystemPromptWithMemory(userInput);
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
                maybeCompactHistory();
                LlmClient.ChatResponse response = llmClient.chat(
                        conversationHistory,
                        toolRegistry.getToolDefinitions()
                );
                budget.recordTokens(response.inputTokens(), response.outputTokens());
                memoryManager.recordTokenUsage(response.inputTokens(), response.outputTokens());

                conversationHistory.add(LlmClient.Message.assistant(
                        response.content(),
                        response.toolCalls()
                ));

                if (!response.hasToolCalls()) {
                    log.info("Agent 完成，返回最终回复");
                    if (response.content() != null && !response.content().isBlank()) {
                        memoryManager.addAssistantMessage(response.content());
                    }
                    return response.content() == null ? "" : response.content();
                }

                budget.recordToolCalls(response.toolCalls());

                List<ToolRegistry.ToolInvocation> invocations = response.toolCalls().stream()
                        .map(tc -> new ToolRegistry.ToolInvocation(tc.id(), tc.name(), tc.arguments()))
                        .toList();

                List<ToolRegistry.ToolExecutionResult> results = toolRegistry.executeTools(invocations);
                for (ToolRegistry.ToolExecutionResult result : results) {
                    conversationHistory.add(LlmClient.Message.tool(result.id(), result.result()));
                    memoryManager.addToolResult(result.name(), result.result());
                    log.debug("工具 {} 执行完成", result.name());
                }
            } catch (Exception e) {
                log.error("ReAct 循环异常", e);
                String errorMsg = "执行过程中出现错误: " + e.getMessage();
                conversationHistory.add(LlmClient.Message.assistant(errorMsg, List.of()));
                memoryManager.addAssistantMessage(errorMsg);
                return errorMsg;
            }
        }
    }

    public void reset() {
        conversationHistory.clear();
        memoryManager.clearShortTerm();
        this.systemPrompt = assembleSystemPrompt(toolRegistry.getProjectPath(), "");
        conversationHistory.add(LlmClient.Message.system(systemPrompt));
        log.info("对话历史已重置");
    }

    public MemoryManager getMemoryManager() {
        return memoryManager;
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

    private void updateSystemPromptWithMemory(String userInput) {
        String memoryContext = memoryManager.buildContextForQuery(
                userInput,
                memoryManager.getTokenBudget().getMemoryContextTokens()
        );
        this.systemPrompt = assembleSystemPrompt(toolRegistry.getProjectPath(), memoryContext);
        if (!conversationHistory.isEmpty() && "system".equals(conversationHistory.get(0).role())) {
            conversationHistory.set(0, LlmClient.Message.system(systemPrompt));
        }
    }

    private String assembleSystemPrompt(String workspacePath, String memoryContext) {
        return promptAssembler.assemble(
                PromptMode.AGENT,
                PromptContext.builder()
                        .workspacePath(workspacePath)
                        .projectMemoryContext(AgentMdLoader.loadForPrompt(Path.of(workspacePath)))
                        .memoryContext(memoryContext)
                        .build()
        );
    }

    private void maybeCompactHistory() {
        int trigger = memoryManager.getTokenBudget().getCompressionTriggerTokens();
        boolean compacted = historyCompactor.compactIfNeeded(conversationHistory, trigger);
        if (compacted) {
            log.info("conversationHistory 已压缩");
        }
    }
}
