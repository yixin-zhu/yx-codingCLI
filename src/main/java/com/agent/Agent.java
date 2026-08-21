package com.agent;

import com.agent.llm.LlmClient;
import com.agent.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent - 基于 ReAct 模式的智能体核心
 * 
 * ReAct 循环:
 *   1. 用户输入 → 添加到对话历史
 *   2. 调用 LLM，传入历史和可用工具
 *   3. 如果 LLM 返回 tool_calls → 执行工具 → 结果回灌 → 回到步骤 2
 *   4. 如果 LLM 返回纯文本 → 返回给用户 → 循环结束
 */
public class Agent {

    private static final Logger log = LoggerFactory.getLogger(Agent.class);
    private static final int MAX_ITERATIONS = 10;  // 最大循环次数，防止死循环

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final List<LlmClient.Message> conversationHistory;
    private String systemPrompt;  // 不再 final，支持动态更新

    /**
     * 构造函数
     */
    public Agent(LlmClient llmClient, ToolRegistry toolRegistry) {
        this(llmClient, toolRegistry, null);
    }

    /**
     * 带自定义系统提示词的构造函数
     */
    public Agent(LlmClient llmClient, ToolRegistry toolRegistry, String customSystemPrompt) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.conversationHistory = new ArrayList<>();
        
        // 构建系统提示词（包含当前工作目录）
        if (customSystemPrompt != null && !customSystemPrompt.isBlank()) {
            this.systemPrompt = customSystemPrompt;
        } else {
            this.systemPrompt = buildSystemPrompt(toolRegistry.getProjectPath());
        }
        
        // 添加系统提示词
        this.conversationHistory.add(LlmClient.Message.system(systemPrompt));
    }

    /**
     * 构建系统提示词（包含工作目录信息）
     */
    private String buildSystemPrompt(String workspacePath) {
        return """
                你是一个强大的编码助手。你的任务是帮助用户完成编程相关的任务。
                
                当前项目根: %s
                
                你可以使用以下工具来帮助用户：
                - read_file: 读取文件内容
                - write_file: 创建或写入文件
                - list_dir: 列出目录内容
                - execute_command: 在项目根目录下执行 Shell 命令
                
                工作原则：
                1. 仔细分析用户需求，制定执行计划
                2. 通过调用工具来获取信息或执行操作
                3. 基于工具返回的结果继续推理
                4. 遇到错误时，尝试不同的方法
                5. 在完成任务后，用简洁的语言向用户总结结果
                
                安全策略（必须遵守）：
                - read_file / write_file / list_dir 的路径必须在当前项目根之内
                - 不要使用 `..` 或绝对路径访问项目根之外的目录
                - 工具返回「路径超出工作目录范围」或「策略拒绝」时，不要原样重试；改用项目内相对路径
                - 用户要求切换项目根时，说明需在 CLI 启动时指定 --workspace 或 AGENT_WORKSPACE，Agent 无法通过文件工具切换项目根
                - 路径优先使用相对路径；写入文件前请确认路径正确
                """.formatted(workspacePath);
    }

    // ==================== 核心方法 ====================

    /**
     * 执行 ReAct 循环
     *
     * @param userInput 用户输入
     * @return 最终回复
     */
    public String run(String userInput) {
        log.info("用户输入: {}", userInput);

        // 1. 添加用户消息到历史
        conversationHistory.add(LlmClient.Message.user(userInput));

        StringBuilder finalResponse = new StringBuilder();

        // 2. ReAct 循环
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            log.debug("ReAct 迭代 #{}", iteration + 1);

            try {
                // 3. 调用 LLM
                LlmClient.ChatResponse response = llmClient.chat(
                        conversationHistory,
                        toolRegistry.getToolDefinitions()
                );

                // 4. 处理 LLM 响应
                String assistantContent = response.content();
                List<LlmClient.ToolCall> toolCalls = response.toolCalls();

                log.debug("LLM 响应 - content: {}, toolCalls: {}", 
                        assistantContent, 
                        toolCalls != null ? toolCalls.size() : 0);

                // 5. 添加 assistant 消息到历史（保留 toolCalls 信息）
                conversationHistory.add(LlmClient.Message.assistant(
                        assistantContent,
                        toolCalls
                ));

                // 6. 如果没有工具调用，说明是最终回复
                if (!response.hasToolCalls()) {
                    log.info("Agent 完成，返回最终回复");
                    return assistantContent;
                }

                // 7. 有工具调用，执行它们
                List<ToolRegistry.ToolInvocation> invocations = toolCalls.stream()
                        .map(tc -> new ToolRegistry.ToolInvocation(tc.id(), tc.name(), tc.arguments()))
                        .toList();

                List<ToolRegistry.ToolExecutionResult> results = toolRegistry.executeTools(invocations);

                // 8. 将工具执行结果回灌到对话历史
                for (ToolRegistry.ToolExecutionResult result : results) {
                    conversationHistory.add(LlmClient.Message.tool(
                            result.id(),
                            result.result()
                    ));
                    log.debug("工具 {} 执行完成: {}", result.name(), result.result().substring(0, Math.min(200, result.result().length())));
                }

                // 9. 继续循环，让 LLM 基于工具结果继续推理

            } catch (Exception e) {
                log.error("ReAct 循环异常", e);
                String errorMsg = "执行过程中出现错误: " + e.getMessage();
                conversationHistory.add(LlmClient.Message.assistant(errorMsg, List.of()));
                return errorMsg;
            }
        }

        // 达到最大迭代次数
        log.warn("达到最大迭代次数限制");
        return "抱歉，我进行了 " + MAX_ITERATIONS + " 轮推理仍未完成任务。请尝试更具体的指令。";
    }

    /**
     * 重置对话历史（使用当前工作目录重建系统提示词）
     */
    public void reset() {
        conversationHistory.clear();
        // 重新构建系统提示词（包含最新的工作目录）
        this.systemPrompt = buildSystemPrompt(toolRegistry.getProjectPath());
        conversationHistory.add(LlmClient.Message.system(systemPrompt));
        log.info("对话历史已重置");
    }

    /**
     * 获取工具注册表
     */
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    /**
     * 获取对话历史（只读）
     */
    public List<LlmClient.Message> getConversationHistory() {
        return List.copyOf(conversationHistory);
    }

    /**
     * 获取可用工具列表
     */
    public List<String> getAvailableTools() {
        return toolRegistry.getToolNames().stream().toList();
    }

    /**
     * 获取系统提示词
     */
    public String getSystemPrompt() {
        return systemPrompt;
    }
}
