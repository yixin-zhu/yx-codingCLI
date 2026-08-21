package com.agent;

import com.agent.llm.LlmClient;
import com.agent.llm.SimpleLlmClient;
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
    private static ToolRegistry toolRegistry;
    private static boolean running = true;

    public static void main(String[] args) {
        printBanner();

        try {
            // 初始化组件
            String apiUrl = System.getenv().getOrDefault("LLM_API_URL", "https://api.deepseek.com");
            String apiKey = System.getenv().getOrDefault("LLM_API_KEY", "");
            String model = System.getenv().getOrDefault("LLM_MODEL", "deepseek-chat");

            // 检查 API Key 配置
            if (apiKey.equals("sk-****") || apiKey.isBlank()) {
                System.out.println("⚠️  警告: 未设置环境变量 LLM_API_KEY");
                System.out.println("   请设置后重新运行: set LLM_API_KEY=your_api_key");
                System.out.println();
                System.out.println("   临时测试: java -Dllm.api.key=your_key -jar agentic-coding-agent.jar");
                System.out.println();
                
                // 尝试从系统属性获取
                String sysPropKey = System.getProperty("llm.api.key");
                if (sysPropKey != null && !sysPropKey.isBlank()) {
                    apiKey = sysPropKey;
                    System.out.println("✓ 已从系统属性获取 API Key");
                } else {
                    System.out.println("将使用演示模式（无法调用 LLM）");
                    apiKey = "";
                }
            }

            // 从系统属性或环境变量覆盖
            String sysApiUrl = System.getProperty("llm.api.url");
            if (sysApiUrl != null && !sysApiUrl.isBlank()) {
                apiUrl = sysApiUrl;
            }
            String sysModel = System.getProperty("llm.model");
            if (sysModel != null && !sysModel.isBlank()) {
                model = sysModel;
            }

            // 确定工作目录（优先级：系统属性 > 环境变量 > 默认 user.dir）
            Path workspace = resolveWorkspace(args);

            System.out.println("配置信息:");
            System.out.println("  API URL: " + apiUrl);
            System.out.println("  Model: " + model);
            System.out.println("  Workspace: " + workspace.toAbsolutePath().normalize());
            System.out.println();

            // 初始化 LLM 客户端和工具注册表
            LlmClient llmClient = new SimpleLlmClient(apiUrl, apiKey, model);
            toolRegistry = new ToolRegistry(workspace);
            agent = new Agent(llmClient, toolRegistry);

            System.out.println("✓ Agent 初始化完成");
            System.out.println("✓ 可用工具: " + agent.getAvailableTools());
            System.out.println("✓ 工作目录: " + toolRegistry.getProjectPath());
            System.out.println();
            printHelp();
            printSeparator();

            // 进入交互循环
            runInteractiveLoop();

        } catch (Exception e) {
            System.err.println("❌ 初始化失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * 解析项目根目录（对齐 PaiCLI：启动时确定 project root，运行中不通过 Agent 工具切换）。
     *
     * 优先级：
     * 1. 系统属性 -Dworkspace.path=...
     * 2. 环境变量 AGENT_WORKSPACE
     * 3. 命令行参数 --workspace / -w
     * 4. 默认 ../agent-workspace（与 agentic-coding-agent 同级）；不存在则自动创建
     */
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

    /**
     * 默认工作区：与 agentic-coding-agent 同级的 agent-workspace。
     * 若从其他目录启动，则优先使用当前目录下的 agent-workspace。
     */
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

    /**
     * 交互式命令循环
     */
    private static void runInteractiveLoop() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
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

                // 处理命令
                if (input.startsWith("/")) {
                    handleCommand(input);
                    continue;
                }

                // 执行 Agent
                executeAgent(input);
                printSeparator();
            }
        } catch (Exception e) {
            System.err.println("❌ 交互循环异常: " + e.getMessage());
        }

        System.out.println("\n👋 再见！");
    }

    /**
     * 处理斜杠命令
     */
    private static void handleCommand(String input) {
        String[] parts = input.split("\\s+", 2);
        String command = parts[0].toLowerCase();
        String payload = parts.length > 1 ? parts[1] : null;

        switch (command) {
            case "/exit", "/quit", "/q" -> {
                System.out.println("👋 再见！");
                running = false;
            }
            case "/reset" -> {
                agent.reset();
                System.out.println("✓ 对话历史已重置");
            }
            case "/help", "/?" -> printHelp();
            case "/history" -> {
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
            case "/tools" -> {
                System.out.println("可用工具: " + agent.getAvailableTools());
            }
            case "/system" -> {
                System.out.println("系统提示词:");
                System.out.println(agent.getSystemPrompt());
            }
            case "/pwd" -> {
                System.out.println("当前项目根: " + toolRegistry.getProjectPath());
                System.out.println("切换项目根请退出后重新启动，并指定 --workspace 或 AGENT_WORKSPACE。");
            }
            default -> System.out.println("未知命令: " + command + "，输入 /help 查看所有命令");
        }
    }

    /**
     * 执行 Agent 并显示结果
     */
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

    // ==================== 显示辅助方法 ====================

    private static void printBanner() {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║     Agentic Coding Agent - Phase 1 MVP                     ║");
        System.out.println("║     基于 ReAct 模式的智能编码助手                           ║");
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
        System.out.println("  /exit         退出程序");
        System.out.println();
        System.out.println("项目根在启动时确定（对齐 PaiCLI，运行中不可通过 Agent 切换）:");
        System.out.println("  默认: ../agent-workspace（与本项目同级，不存在则自动创建）");
        System.out.println("  -Dworkspace.path=D:\\myproject");
        System.out.println("  --workspace D:\\myproject");
        System.out.println("  环境变量: AGENT_WORKSPACE=D:\\myproject");
        System.out.println();
        System.out.println("直接输入文本即可与 Agent 对话，例如:");
        System.out.println("  \"读取当前目录的文件列表\"");
        System.out.println("  \"创建一个 Hello.java 文件\"");
        System.out.println("  \"执行 dir 命令\"");
    }

    private static void printSeparator() {
        System.out.println("─".repeat(60));
    }
}
