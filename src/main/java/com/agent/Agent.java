package com.agent;

import com.agent.llm.LlmClient;
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
    private final List<LlmClient.Message> conversationHistory;
    private String systemPrompt;

    public Agent(LlmClient llmClient, ToolRegistry toolRegistry) {
        this(llmClient, toolRegistry, null);
    }

    public Agent(LlmClient llmClient, ToolRegistry toolRegistry, String customSystemPrompt) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.conversationHistory = new ArrayList<>();

        if (customSystemPrompt != null && !customSystemPrompt.isBlank()) {
            this.systemPrompt = customSystemPrompt;
        } else {
            this.systemPrompt = buildSystemPrompt(toolRegistry.getProjectPath());
        }
        this.conversationHistory.add(LlmClient.Message.system(systemPrompt));
    }

    private String buildSystemPrompt(String workspacePath) {
        return """
                你是一个强大的编码助手。你的任务是帮助用户完成编程相关的任务。
                
                当前项目根: %s
                
                你可以使用以下工具：
                - read_file: 读取文件内容
                - write_file: 创建或写入文件
                - list_dir: 列出目录内容
                - glob_files: 按 glob 模式查找文件，例如 **/*.java
                - grep_code: 按关键字搜索代码，返回文件和行号
                - execute_command: 在项目根目录执行 Shell 命令
                - create_project: 在当前项目根下创建 java/python/node 项目
                
                工具策略：
                - 精确代码定位优先 glob_files → grep_code → read_file
                - 所有文件路径必须在当前项目根之内，使用相对路径
                - 工具返回「🛡️ 策略拒绝」时不要原样重试 ../ 或项目根外路径
                - 用户要求切换项目根时，说明需退出后通过 --workspace 或 AGENT_WORKSPACE 重启
                
                工作原则：
                1. 仔细分析用户需求，制定执行计划
                2. 通过调用工具来获取信息或执行操作
                3. 基于工具返回的结果继续推理
                4. 遇到错误时，尝试不同的方法
                5. 在完成任务后，用简洁的语言向用户总结结果
                """.formatted(workspacePath);
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
        this.systemPrompt = buildSystemPrompt(toolRegistry.getProjectPath());
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
