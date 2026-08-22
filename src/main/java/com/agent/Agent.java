package com.agent;

import com.agent.context.ContextProfile;
import com.agent.llm.LlmClient;
import com.agent.memory.ConversationHistoryCompactor;
import com.agent.memory.MemoryManager;
import com.agent.memory.TokenBudget;
import com.agent.project.AgentMdLoader;
import com.agent.prompt.PromptAssembler;
import com.agent.prompt.PromptContext;
import com.agent.prompt.PromptMode;
import com.agent.skill.SkillContextBuffer;
import com.agent.skill.SkillIndexFormatter;
import com.agent.skill.SkillRegistry;
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
    private SkillRegistry skillRegistry;
    private SkillContextBuffer skillContextBuffer;

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

    public void setSkillRegistry(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
        refreshSkillIndex("");
    }

    public void setSkillContextBuffer(SkillContextBuffer skillContextBuffer) {
        this.skillContextBuffer = skillContextBuffer;
    }

    public SkillRegistry getSkillRegistry() {
        return skillRegistry;
    }

    /** skill 列表变更后刷新 system prompt 中的索引段 */
    public void refreshSkillIndex() {
        refreshSkillIndex("");
    }

    public String run(String userInput) {
        log.info("用户输入: {}", userInput);
        memoryManager.addUserMessage(userInput);
        updateSystemPromptWithMemory(userInput);
        conversationHistory.add(LlmClient.Message.user(prependSkillBodies(userInput)));

        AgentBudget budget = AgentBudget.fromLlmClient(llmClient);

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
                budget.recordTokens(response.inputTokens(), response.outputTokens(), response.cachedInputTokens());
                memoryManager.recordTokenUsage(response.inputTokens(), response.outputTokens(), response.cachedInputTokens());

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
        if (skillContextBuffer != null) {
            skillContextBuffer.clear();
        }
        this.systemPrompt = assembleSystemPrompt(toolRegistry.getProjectPath(), "");
        conversationHistory.add(LlmClient.Message.system(systemPrompt));
        log.info("对话历史已重置");
    }

    public String getContextStatus() {
        ContextProfile profile = memoryManager.getContextProfile();
        int window = profile.maxContextWindow();
        int triggerTokens = profile.compressionTriggerTokens();

        int systemTokens = 0;
        int userTokens = 0;
        int assistantTokens = 0;
        int toolTokens = 0;
        int systemCount = 0;
        int userCount = 0;
        int assistantCount = 0;
        int toolCount = 0;
        for (LlmClient.Message msg : conversationHistory) {
            int tokens = TokenBudget.estimateMessagesTokens(List.of(msg));
            switch (msg.role()) {
                case "system" -> {
                    systemTokens += tokens;
                    systemCount++;
                }
                case "user" -> {
                    userTokens += tokens;
                    userCount++;
                }
                case "assistant" -> {
                    assistantTokens += tokens;
                    assistantCount++;
                }
                case "tool" -> {
                    toolTokens += tokens;
                    toolCount++;
                }
                default -> {
                }
            }
        }
        int messagesTokens = userTokens + assistantTokens + toolTokens;
        int toolsSchemaTokens = estimateToolsSchemaTokens();
        int total = systemTokens + messagesTokens + toolsSchemaTokens;
        double ratio = window > 0 ? (double) total / window : 0;
        int triggerRemaining = Math.max(0, triggerTokens - total);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("模型: %s (%s)   window: %,d%n",
                llmClient.getModelName(), llmClient.getProviderName(), window));
        sb.append(String.format("上下文占用: %,d / %,d (%.1f%%)%n", total, window, ratio * 100));
        sb.append(String.format("  System prompt: %,d (%d 条)%n", systemTokens, systemCount));
        sb.append(String.format("  Tools schema:  %,d%n", toolsSchemaTokens));
        sb.append(String.format("  Conversation:  %,d (%d 条: user=%d assistant=%d tool=%d)%n",
                messagesTokens, userCount + assistantCount + toolCount, userCount, assistantCount, toolCount));
        sb.append(String.format("压缩阈值: %,d (%d%%)   距压缩还有: %,d%n",
                triggerTokens, (int) (profile.compressionTriggerRatio() * 100), triggerRemaining));
        sb.append("MCP resource 自动索引: ")
                .append(profile.mcpResourceIndexEnabled() ? "开启" : "关闭（window 不足 32k）")
                .append("\n");
        sb.append("prompt cache: ").append(profile.promptCacheMode()).append("\n");
        sb.append("Agent 软提示预算: ").append(profile.agentTokenBudget()).append("\n");
        sb.append("\n").append(memoryManager.getSystemStatus());
        return sb.toString();
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
                memoryManager.getContextProfile().memoryContextTokens()
        );
        refreshSkillIndex(memoryContext);
    }

    private void refreshSkillIndex(String memoryContext) {
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
                        .skillIndex(buildSkillIndex())
                        .build()
        );
    }

    private String buildSkillIndex() {
        if (skillRegistry == null) {
            return "";
        }
        try {
            return SkillIndexFormatter.format(skillRegistry.enabledSkills());
        } catch (Exception e) {
            log.warn("构建 skill 索引失败", e);
            return "";
        }
    }

    private String prependSkillBodies(String userInput) {
        if (skillContextBuffer == null || skillContextBuffer.isEmpty()) {
            return userInput;
        }
        String drained = skillContextBuffer.drain();
        if (drained.isEmpty()) {
            return userInput;
        }
        return drained + "\n用户输入：\n" + userInput;
    }

    private void maybeCompactHistory() {
        int trigger = memoryManager.getContextProfile().compressionTriggerTokens();
        boolean compacted = historyCompactor.compactIfNeeded(conversationHistory, trigger);
        if (compacted) {
            log.info("conversationHistory 已压缩（阈值 {} tokens）", trigger);
        }
    }

    private int estimateToolsSchemaTokens() {
        try {
            return com.agent.memory.MemoryEntry.estimateTokens(
                    LlmClient.MAPPER.writeValueAsString(toolRegistry.getToolDefinitions()));
        } catch (Exception e) {
            return 0;
        }
    }
}
