package com.agent;

import com.agent.cli.CliCommandParser;
import com.agent.cli.CliCommandParser.CommandType;
import com.agent.cli.CliCommandParser.ParsedCommand;
import com.agent.cli.PlanReviewInputParser;
import com.agent.llm.LlmClient;
import com.agent.llm.SimpleLlmClient;
import com.agent.memory.MemoryEntry;
import com.agent.memory.MemoryManager;
import com.agent.plan.ExecutionPlan;
import com.agent.tool.ToolRegistry;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * CLI 入口 - 简单的交互式命令行界面
 */
public class Main {

    private static Agent agent;
    private static PlanExecuteAgent planAgent;
    private static AgentOrchestrator teamAgent;
    private static ToolRegistry toolRegistry;
    private static LlmClient llmClient;
    private static BufferedReader inputReader;
    private static boolean running = true;

    public static void main(String[] args) {
        printBanner();

        try {
            String apiUrl = System.getenv().getOrDefault("LLM_API_URL", "https://api.deepseek.com");
            String apiKey = System.getenv().getOrDefault("LLM_API_KEY", "");
            String model = System.getenv().getOrDefault("LLM_MODEL", "deepseek-chat");

            if (apiKey.equals("sk-****") || apiKey.isBlank()) {
                System.out.println("⚠️  警告: 未设置环境变量 LLM_API_KEY");
                System.out.println("   请设置后重新运行: set LLM_API_KEY=your_api_key");
                System.out.println();
                System.out.println("   临时测试: java -Dllm.api.key=your_key -jar agentic-coding-agent.jar");
                System.out.println();

                String sysPropKey = System.getProperty("llm.api.key");
                if (sysPropKey != null && !sysPropKey.isBlank()) {
                    apiKey = sysPropKey;
                    System.out.println("✓ 已从系统属性获取 API Key");
                } else {
                    System.out.println("将使用演示模式（无法调用 LLM）");
                    apiKey = "";
                }
            }

            String sysApiUrl = System.getProperty("llm.api.url");
            if (sysApiUrl != null && !sysApiUrl.isBlank()) {
                apiUrl = sysApiUrl;
            }
            String sysModel = System.getProperty("llm.model");
            if (sysModel != null && !sysModel.isBlank()) {
                model = sysModel;
            }

            Path workspace = resolveWorkspace(args);

            System.out.println("配置信息:");
            System.out.println("  API URL: " + apiUrl);
            System.out.println("  Model: " + model);
            System.out.println("  Workspace: " + workspace.toAbsolutePath().normalize());
            System.out.println();

            llmClient = new SimpleLlmClient(apiUrl, apiKey, model);
            toolRegistry = new ToolRegistry(workspace);
            agent = new Agent(llmClient, toolRegistry);
            planAgent = new PlanExecuteAgent(llmClient, toolRegistry, Main::reviewPlan);
            teamAgent = new AgentOrchestrator(llmClient, toolRegistry, agent.getMemoryManager());

            System.out.println("✓ Agent 初始化完成");
            System.out.println("✓ 可用工具: " + agent.getAvailableTools());
            System.out.println("✓ 工作目录: " + toolRegistry.getProjectPath());
            System.out.println();
            printHelp();
            printSeparator();

            runInteractiveLoop();

        } catch (Exception e) {
            System.err.println("❌ 初始化失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static Path resolveWorkspace(String[] args) throws java.io.IOException {
        Path fromProperty = resolveWorkspaceFromProperty("workspace.path", System.getProperty("workspace.path"));
        if (fromProperty != null) {
            return fromProperty;
        }

        Path fromEnv = resolveWorkspaceFromProperty("AGENT_WORKSPACE", System.getenv("AGENT_WORKSPACE"));
        if (fromEnv != null) {
            return fromEnv;
        }

        Path fromArg = resolveWorkspaceFromArgs(args);
        if (fromArg != null) {
            return fromArg;
        }

        return ensureDefaultWorkspace();
    }

    private static Path resolveWorkspaceFromProperty(String label, String rawPath) throws java.io.IOException {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        Path path = Paths.get(rawPath).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            System.out.println("⚠️  " + label + " 指定的目录不存在: " + path);
            return null;
        }
        if (!Files.isDirectory(path)) {
            System.out.println("⚠️  " + label + " 指定的路径不是目录: " + path);
            return null;
        }
        return path;
    }

    private static Path resolveWorkspaceFromArgs(String[] args) throws java.io.IOException {
        if (args == null) {
            return null;
        }
        for (int i = 0; i < args.length; i++) {
            if ("--workspace".equals(args[i]) || "-w".equals(args[i])) {
                if (i + 1 >= args.length) {
                    System.out.println("⚠️  --workspace 需要指定目录路径");
                    return null;
                }
                return resolveWorkspaceFromProperty("命令行参数", args[i + 1]);
            }
        }
        return null;
    }

    private static Path ensureDefaultWorkspace() throws java.io.IOException {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path candidate = cwd.getFileName() != null
                && "agentic-coding-agent".equals(cwd.getFileName().toString())
                ? cwd.resolveSibling("agent-workspace")
                : cwd.resolve("agent-workspace");
        if (!Files.exists(candidate)) {
            Files.createDirectories(candidate);
            System.out.println("✓ 已创建默认工作目录: " + candidate);
        }
        return candidate.toAbsolutePath().normalize();
    }

    private static void runInteractiveLoop() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            inputReader = reader;
            while (running) {
                System.out.print("🤖 > ");
                System.out.flush();

                String input = reader.readLine();
                if (input == null) {
                    break;
                }

                input = input.trim();
                if (input.isEmpty()) {
                    continue;
                }

                if (input.startsWith("/")) {
                    ParsedCommand command = CliCommandParser.parse(input);
                    if (command.type() == CommandType.NONE) {
                        continue;
                    }
                    handleCommand(command);
                    continue;
                }

                executeAgent(input);
                printSeparator();
            }
        } catch (Exception e) {
            System.err.println("❌ 交互循环异常: " + e.getMessage());
        }

        System.out.println("\n👋 再见！");
    }

    private static void handleCommand(ParsedCommand command) {
        switch (command.type()) {
            case EXIT -> {
                System.out.println("👋 再见！");
                running = false;
            }
            case RESET -> {
                agent.reset();
                System.out.println("✓ 对话历史已重置");
            }
            case HELP -> printHelp();
            case HISTORY -> {
                var history = agent.getConversationHistory();
                System.out.println("对话历史 (" + history.size() + " 条消息):");
                for (int i = 0; i < history.size(); i++) {
                    var msg = history.get(i);
                    String preview = msg.content() != null
                            ? (msg.content().length() > 80 ? msg.content().substring(0, 80) + "..." : msg.content())
                            : "(无内容)";
                    System.out.printf("  [%d] %s: %s%n", i, msg.role(), preview);
                }
            }
            case TOOLS -> System.out.println("可用工具: " + agent.getAvailableTools());
            case SYSTEM -> {
                System.out.println("系统提示词:");
                System.out.println(agent.getSystemPrompt());
            }
            case PWD -> {
                System.out.println("当前项目根: " + toolRegistry.getProjectPath());
                System.out.println("切换项目根请退出后重新启动，并指定 --workspace 或 AGENT_WORKSPACE。");
            }
            case PLAN -> executePlan(command.payload());
            case TEAM -> executeTeam(command.payload());
            case SAVE -> handleSave(command.payload());
            case MEMORY_STATUS -> System.out.println(agent.getMemoryManager().getSystemStatus());
            case MEMORY_LIST -> printMemoryList(agent.getMemoryManager().listLongTerm());
            case MEMORY_SEARCH -> handleMemorySearch(command.payload());
            case MEMORY_DELETE -> handleMemoryDelete(command.payload());
            case MEMORY_CLEAR -> {
                agent.getMemoryManager().clearLongTerm();
                System.out.println("✓ 长期记忆已清空");
            }
            case UNKNOWN -> System.out.println("未知命令: " + command.payload() + "，输入 /help 查看所有命令");
            default -> {
            }
        }
    }

    private static void handleSave(String payload) {
        if (payload == null || payload.isBlank()) {
            System.out.println("用法: /save <事实>  或  /save --global <事实>");
            return;
        }
        MemoryManager memoryManager = agent.getMemoryManager();
        if (payload.startsWith("--global ")) {
            memoryManager.storeFact(payload.substring("--global ".length()).trim(), "global");
            System.out.println("✓ 已保存到 global 长期记忆");
            return;
        }
        memoryManager.storeFact(payload.trim(), "project");
        System.out.println("✓ 已保存到 project 长期记忆");
    }

    private static void handleMemorySearch(String query) {
        if (query == null || query.isBlank()) {
            System.out.println("用法: /memory search <关键词>");
            return;
        }
        printMemoryList(agent.getMemoryManager().searchLongTerm(query, 20));
    }

    private static void handleMemoryDelete(String id) {
        if (id == null || id.isBlank()) {
            System.out.println("用法: /memory delete <id>");
            return;
        }
        if (agent.getMemoryManager().deleteLongTerm(id)) {
            System.out.println("✓ 已删除记忆: " + id);
        } else {
            System.out.println("未找到记忆: " + id);
        }
    }

    private static void printMemoryList(java.util.List<MemoryEntry> entries) {
        if (entries.isEmpty()) {
            System.out.println("（无长期记忆）");
            return;
        }
        for (MemoryEntry entry : entries) {
            System.out.printf("  [%s] %s (%s)%n",
                    entry.getId(),
                    entry.getContent(),
                    entry.getMetadata().getOrDefault("scope", "global"));
        }
    }

    private static void executeTeam(String goal) {
        if (goal == null || goal.isBlank()) {
            System.out.println("请提供团队任务，例如: /team 审查并修复 AgentTest 里的一个断言问题");
            return;
        }

        System.out.println("\n👥 使用 Multi-Agent 协作模式\n");
        try {
            long startTime = System.currentTimeMillis();
            String response = teamAgent.run(goal);
            long elapsed = System.currentTimeMillis() - startTime;

            System.out.println("\n" + response);
            System.out.printf("\n⏱️  耗时: %.1f 秒%n", elapsed / 1000.0);
            printSeparator();
        } catch (Exception e) {
            System.err.println("\n❌ 团队协作失败: " + e.getMessage());
        }
    }

    private static void executePlan(String goal) {
        if (goal == null || goal.isBlank()) {
            System.out.println("请提供计划任务，例如: /plan 创建 demo 项目，然后读取 pom.xml");
            return;
        }

        System.out.println("\n📋 使用 Plan-and-Execute 模式\n");
        try {
            long startTime = System.currentTimeMillis();
            String response = planAgent.run(goal);
            long elapsed = System.currentTimeMillis() - startTime;

            System.out.println("\n" + response);
            System.out.printf("\n⏱️  耗时: %.1f 秒%n", elapsed / 1000.0);
            printSeparator();
        } catch (Exception e) {
            System.err.println("\n❌ 计划执行失败: " + e.getMessage());
        }
    }

    private static PlanExecuteAgent.PlanReviewDecision reviewPlan(String goal, ExecutionPlan plan) {
        System.out.println(plan.summarize());
        if (plan.getSummary() != null && !plan.getSummary().isBlank()) {
            System.out.println("   - 摘要: " + plan.getSummary());
        }
        System.out.println("📝 计划已生成。");
        System.out.println("   - 直接回车 或 输入 y：执行");
        System.out.println("   - 输入 esc / cancel：取消");
        System.out.println("   - 输入 /view：展开完整计划");
        System.out.println("   - 其他文字：作为补充要求重新规划\n");

        try {
            while (true) {
                System.out.print("操作> ");
                System.out.flush();
                String input = inputReader == null ? "" : inputReader.readLine();
                if (input == null) {
                    return PlanExecuteAgent.PlanReviewDecision.cancel();
                }
                if ("/view".equalsIgnoreCase(input.trim())) {
                    System.out.println(plan.visualize());
                    continue;
                }

                PlanReviewInputParser.Decision decision = PlanReviewInputParser.parse(input);
                return switch (decision.type()) {
                    case EXECUTE -> PlanExecuteAgent.PlanReviewDecision.execute();
                    case CANCEL -> PlanExecuteAgent.PlanReviewDecision.cancel();
                    case SUPPLEMENT -> PlanExecuteAgent.PlanReviewDecision.supplement(decision.feedback());
                };
            }
        } catch (Exception e) {
            System.err.println("❌ 计划审阅失败: " + e.getMessage());
            return PlanExecuteAgent.PlanReviewDecision.cancel();
        }
    }

    private static void executeAgent(String userInput) {
        System.out.println("\n⏳ 思考中...");

        try {
            long startTime = System.currentTimeMillis();
            String response = agent.run(userInput);
            long elapsed = System.currentTimeMillis() - startTime;

            System.out.println("\n" + response);
            System.out.printf("\n⏱️  耗时: %.1f 秒%n", elapsed / 1000.0);

        } catch (Exception e) {
            System.err.println("\n❌ 执行失败: " + e.getMessage());
        }
    }

    private static void printBanner() {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║     Agentic Coding Agent - Phase 5 MVP                     ║");
        System.out.println("║     ReAct + Plan + Memory + Multi-Agent 智能编码助手         ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    private static void printHelp() {
        System.out.println("命令列表:");
        System.out.println("  /help         显示帮助信息");
        System.out.println("  /reset        重置对话历史");
        System.out.println("  /history      查看对话历史");
        System.out.println("  /tools        查看可用工具");
        System.out.println("  /system       查看系统提示词");
        System.out.println("  /pwd          查看当前项目根");
        System.out.println("  /plan <任务>  使用 Plan-and-Execute 模式执行复杂任务");
        System.out.println("  /team <任务>  使用 Multi-Agent 协作（规划/执行/审查）");
        System.out.println("  /save <事实>  手动保存长期记忆（/save --global 保存跨项目偏好）");
        System.out.println("  /memory       查看记忆系统状态");
        System.out.println("  /memory list/search/delete/clear  管理长期记忆");
        System.out.println("  /exit         退出程序");
        System.out.println();
        System.out.println("项目根在启动时确定（对齐 PaiCLI，运行中不可通过 Agent 切换）:");
        System.out.println("  默认: ../agent-workspace（与本项目同级，不存在则自动创建）");
        System.out.println("  -Dworkspace.path=D:\\myproject");
        System.out.println("  --workspace D:\\myproject");
        System.out.println("  环境变量: AGENT_WORKSPACE=D:\\myproject");
        System.out.println();
        System.out.println("直接输入文本走 ReAct 模式，例如:");
        System.out.println("  \"读取当前目录的文件列表\"");
        System.out.println("复杂多步任务请用 /plan 或 /team，例如:");
        System.out.println("  /plan 创建 demo 项目，然后读取 pom.xml，最后验证项目结构");
        System.out.println("  /team 审查并修复 AgentTest 里的一个断言问题");
    }

    private static void printSeparator() {
        System.out.println("─".repeat(60));
    }
}
