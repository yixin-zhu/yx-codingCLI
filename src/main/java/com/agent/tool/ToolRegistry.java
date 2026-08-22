package com.agent.tool;

import com.agent.llm.LlmClient;
import com.agent.policy.AuditLog;
import com.agent.policy.CommandGuard;
import com.agent.policy.PathGuard;
import com.agent.policy.PolicyException;
import com.agent.web.FetchResult;
import com.agent.web.HtmlExtractor;
import com.agent.web.NetworkPolicy;
import com.agent.web.SearchProvider;
import com.agent.web.SearchProviderFactory;
import com.agent.web.SearchResult;
import com.agent.web.WebFetcher;
import com.agent.mcp.protocol.McpToolDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具注册表 - 管理所有可用工具
 */
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);
    private static final int MAX_PARALLEL_TOOLS = 4;
    static final long DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS = 90;
    private static final int DEFAULT_FETCH_MAX_CHARS = 8_000;

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Map<String, McpRegisteredTool> mcpTools = new ConcurrentHashMap<>();
    private final long toolBatchTimeoutSeconds;
    private final AuditLog auditLog = new AuditLog();
    private Path workingDirectory;
    private PathGuard pathGuard;
    private FileTools fileTools;
    private BiConsumer<String, String> memorySaver;
    private SearchProvider searchProvider;
    private WebFetcher webFetcher;
    private HtmlExtractor htmlExtractor;
    private NetworkPolicy networkPolicy;

    record Tool(String name, String description, JsonNode parameters, ToolExecutor executor) {}

    @FunctionalInterface
    interface ToolExecutor {
        String execute(Map<String, String> args) throws Exception;
    }

    public record ToolInvocation(String id, String name, String argumentsJson) {}

    public record ToolExecutionResult(String id, String name, String result, long elapsedMillis, boolean timedOut) {
        public ToolExecutionResult(String id, String name, String result, long elapsedMillis) {
            this(id, name, result, elapsedMillis, false);
        }

        static ToolExecutionResult failed(ToolInvocation invocation, String message) {
            return new ToolExecutionResult(
                    invocation.id(), invocation.name(), "工具执行失败: " + message, 0, false);
        }

        static ToolExecutionResult timedOut(ToolInvocation invocation, long timeoutSeconds) {
            return new ToolExecutionResult(
                    invocation.id(),
                    invocation.name(),
                    "工具执行超时（" + timeoutSeconds + "秒），已取消",
                    timeoutSeconds * 1000,
                    true
            );
        }
    }

    private record Param(String name, String type, String description, boolean required) {}

    private record McpRegisteredTool(McpToolDescriptor descriptor, Function<String, String> invoker) {}

    public ToolRegistry() {
        this(Paths.get(System.getProperty("user.dir")));
    }

    public ToolRegistry(Path workingDirectory) {
        this(workingDirectory, DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS);
    }

    ToolRegistry(Path workingDirectory, long toolBatchTimeoutSeconds) {
        this.toolBatchTimeoutSeconds = toolBatchTimeoutSeconds;
        setProjectPath(workingDirectory);
        registerDefaultTools();
    }

    private void registerDefaultTools() {
        registerReadFile();
        registerWriteFile();
        registerListDir();
        registerGlobFiles();
        registerGrepCode();
        registerExecuteCommand();
        registerCreateProject();
        registerSaveMemory();
        registerWebTools();
    }

    private void registerReadFile() {
        registerTool(new Tool(
                "read_file",
                "读取指定文件的内容。适用于查看代码文件、配置文件等。",
                createParameters(new Param("path", "string", "要读取的文件路径（相对项目根）", true)),
                args -> fileTools.readFile(args)
        ));
    }

    private void registerWriteFile() {
        registerTool(new Tool(
                "write_file",
                "将内容写入指定文件。如果文件不存在会创建，如果存在会覆盖。",
                createParameters(
                        new Param("path", "string", "要写入的文件路径（相对项目根）", true),
                        new Param("content", "string", "要写入文件的内容", true)
                ),
                args -> fileTools.writeFile(args)
        ));
    }

    private void registerListDir() {
        ObjectNode params = LlmClient.MAPPER.createObjectNode();
        params.put("type", "object");
        ObjectNode properties = LlmClient.MAPPER.createObjectNode();
        properties.set("path", stringProperty("要列出的目录路径（默认为 .）"));
        properties.set("recursive", boolProperty("是否递归列出子目录（默认 false）"));
        params.set("properties", properties);

        registerTool(new Tool(
                "list_dir",
                "列出指定目录下的文件和子目录。",
                params,
                args -> fileTools.listDir(args)
        ));
    }

    private void registerGlobFiles() {
        registerTool(new Tool(
                "glob_files",
                "按 glob 模式查找项目内文件，适合先定位候选文件，例如 **/*.java",
                createParameters(
                        new Param("pattern", "string", "glob 模式，例如 **/*.java", true),
                        new Param("path", "string", "搜索起始目录，默认 .", false),
                        new Param("max_results", "integer", "最多返回结果数，默认 50，上限 200", false)
                ),
                args -> fileTools.globFiles(args)
        ));
    }

    private void registerGrepCode() {
        registerTool(new Tool(
                "grep_code",
                "在项目内按关键字搜索代码，返回文件和行号；精确符号定位优先于语义搜索",
                createParameters(
                        new Param("pattern", "string", "要搜索的关键字", true),
                        new Param("path", "string", "搜索起始目录，默认 .", false),
                        new Param("glob", "string", "可选文件 glob 过滤，例如 **/*.java", false),
                        new Param("max_results", "integer", "最多返回命中数，默认 50", false)
                ),
                args -> fileTools.grepCode(args)
        ));
    }

    private void registerExecuteCommand() {
        ObjectNode params = LlmClient.MAPPER.createObjectNode();
        params.put("type", "object");
        ObjectNode properties = LlmClient.MAPPER.createObjectNode();
        properties.set("command", stringProperty("要执行的 shell 命令"));
        properties.set("timeout", integerProperty("命令超时时间（秒，默认 30）"));
        params.set("properties", properties);
        params.putArray("required").add("command");

        registerTool(new Tool(
                "execute_command",
                "在当前项目根目录执行短时 Shell 命令并返回输出。",
                params,
                args -> {
                    String command = args.get("command");
                    int timeout = parseInt(args.get("timeout"), 30);
                    log.info("执行命令: {}", command);

                    String denyReason = CommandGuard.check(command);
                    if (denyReason != null) {
                        throw new PolicyException(denyReason);
                    }

                    String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
                    ProcessBuilder pb = os.contains("win")
                            ? new ProcessBuilder("cmd", "/c", command)
                            : new ProcessBuilder("bash", "-c", command);

                    pb.directory(pathGuard.getRootPath().toFile());
                    pb.redirectErrorStream(true);
                    pb.environment().put("JAVA_TOOL_OPTIONS", "-Dfile.encoding=UTF-8");

                    Process process = pb.start();
                    StringBuilder output = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            output.append(line).append("\n");
                        }
                    }

                    boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
                    if (!finished) {
                        process.destroyForcibly();
                        return "错误: 命令执行超时（" + timeout + "秒）\n已输出: " + output;
                    }
                    if (process.exitValue() != 0) {
                        return "命令执行失败 (exit code: " + process.exitValue() + ")\n输出:\n" + output;
                    }
                    return output.toString();
                }
        ));
    }

    private void registerCreateProject() {
        registerTool(new Tool(
                "create_project",
                "在当前项目根下创建新项目结构",
                createParameters(
                        new Param("name", "string", "项目名称（相对项目根的子目录名）", true),
                        new Param("type", "string", "项目类型: java / python / node", true)
                ),
                args -> {
                    String name = args.get("name");
                    String type = args.get("type").toLowerCase(Locale.ROOT);
                    Path projectRoot = pathGuard.resolveSafe(name);
                    Files.createDirectories(projectRoot);

                    switch (type) {
                        case "java" -> {
                            Files.createDirectories(projectRoot.resolve("src/main/java"));
                            Files.createDirectories(projectRoot.resolve("src/main/resources"));
                            Files.writeString(projectRoot.resolve("pom.xml"), """
                                    <?xml version="1.0" encoding="UTF-8"?>
                                    <project>
                                        <modelVersion>4.0.0</modelVersion>
                                        <groupId>com.example</groupId>
                                        <artifactId>%s</artifactId>
                                        <version>1.0</version>
                                    </project>
                                    """.formatted(name));
                        }
                        case "python" -> {
                            Files.writeString(projectRoot.resolve("main.py"), "# 主程序入口\n");
                            Files.writeString(projectRoot.resolve("requirements.txt"), "# 依赖列表\n");
                        }
                        case "node" -> Files.writeString(projectRoot.resolve("package.json"),
                                "{\"name\": \"%s\", \"version\": \"1.0.0\"}".formatted(name));
                        default -> throw new IllegalArgumentException("不支持的项目类型: " + type);
                    }
                    return "项目已创建: " + name + " (类型: " + type + ")";
                }
        ));
    }

    private void registerWebTools() {
        registerTool(new Tool(
                "web_search",
                "搜索互联网，获取实时信息（最新版本、官方文档、技术资讯等）。"
                        + "需配置 SERPAPI_KEY 或 SEARXNG_URL。",
                createParameters(
                        new Param("query", "string", "搜索关键词，例如'Java 21 新特性'", true),
                        new Param("top_k", "integer", "返回结果数量（默认5）", false)
                ),
                args -> webSearch(args.get("query"), parseInt(args.get("top_k"), 5))
        ));

        registerTool(new Tool(
                "web_fetch",
                "抓取指定 URL，提取正文转 Markdown。适用静态/SSR 页面；JS 渲染页可能返回空正文。",
                createParameters(
                        new Param("url", "string", "完整 URL，需 http 或 https 协议", true),
                        new Param("max_chars", "integer", "返回 Markdown 最大字符数（默认 8000）", false)
                ),
                args -> webFetch(args.get("url"), parseInt(args.get("max_chars"), DEFAULT_FETCH_MAX_CHARS))
        ));
    }

    private synchronized SearchProvider searchProvider() {
        if (searchProvider == null) {
            searchProvider = SearchProviderFactory.create();
        }
        return searchProvider;
    }

    private synchronized WebFetcher webFetcher() {
        if (webFetcher == null) {
            webFetcher = new WebFetcher();
        }
        return webFetcher;
    }

    private synchronized HtmlExtractor htmlExtractor() {
        if (htmlExtractor == null) {
            htmlExtractor = new HtmlExtractor();
        }
        return htmlExtractor;
    }

    private synchronized NetworkPolicy networkPolicy() {
        if (networkPolicy == null) {
            networkPolicy = new NetworkPolicy();
        }
        return networkPolicy;
    }

    String webSearch(String query, int topK) {
        if (query == null || query.isBlank()) {
            return "搜索关键词不能为空";
        }
        SearchProvider provider = searchProvider();
        if (!provider.isReady()) {
            return "⚠️ " + provider.unavailableHint();
        }
        try {
            List<SearchResult> results = provider.search(query.trim(), topK);
            return formatSearchResults(provider.name(), query, results);
        } catch (Exception e) {
            return "搜索失败 (" + provider.name() + "): " + e.getMessage();
        }
    }

    String webFetch(String url, int maxChars) {
        if (url == null || url.isBlank()) {
            return "URL 不能为空";
        }
        NetworkPolicy policy = networkPolicy();
        String denyReason = policy.checkUrl(url);
        if (denyReason != null) {
            return "❌ 网络访问被拒绝: " + denyReason;
        }
        String rateReason = policy.acquire();
        if (rateReason != null) {
            return "❌ " + rateReason;
        }

        try {
            WebFetcher.RawResponse raw = webFetcher().fetch(url.trim());
            HtmlExtractor.Extracted extracted = htmlExtractor().extract(raw.body(), raw.url());
            String markdown = extracted.markdown();
            int originalLength = markdown.length();
            boolean truncated = false;
            if (maxChars > 0 && markdown.length() > maxChars) {
                markdown = markdown.substring(0, maxChars);
                truncated = true;
            }
            FetchResult result = FetchResult.ok(raw.url(), extracted.title(), markdown, originalLength, truncated);
            if (raw.truncated()) {
                return formatFetchResult(result) + "\n\n⚠️ 原始 HTML 响应超过 5MB，已截断后再提取正文。";
            }
            return formatFetchResult(result);
        } catch (Exception e) {
            return "抓取失败: " + e.getMessage();
        }
    }

    private String formatSearchResults(String providerName, String query, List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "🔍 [" + providerName + "] " + query + "\n\n未找到相关结果。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("🔍 [").append(providerName).append("] ").append(query).append("\n\n");
        for (SearchResult r : results) {
            sb.append(r.position()).append(". ").append(r.title()).append("\n");
            if (!r.snippet().isBlank()) {
                String snippet = r.snippet();
                if (snippet.length() > 200) {
                    snippet = snippet.substring(0, 200) + "...";
                }
                sb.append("   ").append(snippet).append("\n");
            }
            if (!r.url().isBlank()) {
                sb.append("   🔗 ").append(r.url());
                if (!r.source().isBlank()) {
                    sb.append("  (").append(r.source()).append(")");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private String formatFetchResult(FetchResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("🌐 抓取: ").append(result.url()).append("\n");
        if (!result.title().isBlank()) {
            sb.append("📄 标题: ").append(result.title()).append("\n");
        }
        if (result.bodyEmpty()) {
            sb.append("\n⚠️ ").append(result.hint()).append("\n");
            return sb.toString();
        }
        sb.append("📏 正文 ").append(result.contentLength()).append(" 字符");
        if (result.truncated()) {
            sb.append("（已截断）");
        }
        sb.append("\n\n---\n\n");
        sb.append(result.markdown());
        return sb.toString();
    }

    private void registerSaveMemory() {
        registerTool(new Tool(
                "save_memory",
                "当用户明确说“记一下”“记住”“以后记得”或要求保存长期偏好/稳定事实时调用；scope 默认 project，跨项目偏好才用 global。",
                createParameters(
                        new Param("fact", "string", "要长期保存的稳定事实或用户偏好", true),
                        new Param("scope", "string", "记忆作用域：project 或 global，默认 project", false)
                ),
                args -> {
                    String fact = args.get("fact");
                    if (fact == null || fact.isBlank()) {
                        return "保存长期记忆失败: fact 不能为空";
                    }
                    if (memorySaver == null) {
                        return "保存长期记忆失败: 记忆保存器未初始化";
                    }
                    String scope = "global".equalsIgnoreCase(args.get("scope")) ? "global" : "project";
                    memorySaver.accept(fact.trim(), scope);
                    return "💾 已保存到长期记忆(" + scope + "): " + fact.trim();
                }
        ));
    }

    public void setMemorySaver(BiConsumer<String, String> memorySaver) {
        this.memorySaver = memorySaver;
    }

    public void registerTool(Tool tool) {
        tools.put(tool.name(), tool);
        log.debug("注册工具: {}", tool.name());
    }

    public boolean hasTool(String toolName) {
        return tools.containsKey(toolName);
    }

    public synchronized void registerMcpTool(McpToolDescriptor descriptor, Function<String, String> invoker) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(invoker, "invoker");
        String toolName = descriptor.namespacedName();
        mcpTools.put(toolName, new McpRegisteredTool(descriptor, invoker));
        tools.put(toolName, new Tool(
                toolName,
                mcpDescription(descriptor),
                descriptor.inputSchema(),
                args -> "MCP 工具不应通过 Map<String,String> 入口执行"
        ));
        log.debug("注册 MCP 工具: {}", toolName);
    }

    public synchronized void unregisterMcpTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return;
        }
        mcpTools.remove(toolName);
        tools.remove(toolName);
    }

    public synchronized void replaceMcpToolsForServer(String serverName, List<McpToolDescriptor> newTools,
                                                      Function<McpToolDescriptor, Function<String, String>> invokerFactory) {
        Objects.requireNonNull(serverName, "serverName");
        Objects.requireNonNull(newTools, "newTools");
        Objects.requireNonNull(invokerFactory, "invokerFactory");
        String prefix = "mcp__" + serverName + "__";
        List<String> existing = mcpTools.keySet().stream()
                .filter(name -> name.startsWith(prefix))
                .toList();
        for (String toolName : existing) {
            mcpTools.remove(toolName);
            tools.remove(toolName);
        }
        for (McpToolDescriptor descriptor : newTools) {
            registerMcpTool(descriptor, invokerFactory.apply(descriptor));
        }
    }

    private static String mcpDescription(McpToolDescriptor descriptor) {
        String description = descriptor.description();
        if (description == null || description.isBlank()) {
            return "MCP 工具 (" + descriptor.serverName() + "/" + descriptor.name() + ")";
        }
        return "[MCP:" + descriptor.serverName() + "] " + description;
    }

    public List<LlmClient.Tool> getToolDefinitions() {
        return tools.values().stream()
                .map(tool -> new LlmClient.Tool(tool.name(), tool.description(), tool.parameters()))
                .collect(Collectors.toList());
    }

    public Optional<Tool> getTool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public Set<String> getToolNames() {
        return tools.keySet();
    }

    /**
     * 并行执行同一轮 LLM 返回的多个工具调用。
     * 结果按传入顺序返回，便于按原 tool_call 顺序回灌消息历史。
     */
    public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations) {
        if (invocations == null || invocations.isEmpty()) {
            return List.of();
        }
        if (invocations.size() == 1) {
            return List.of(executeTool(invocations.get(0)));
        }

        int parallelism = Math.min(invocations.size(), MAX_PARALLEL_TOOLS);
        ExecutorService executor = Executors.newFixedThreadPool(parallelism, r -> {
            Thread thread = new Thread(r, "agent-tool-executor");
            thread.setDaemon(true);
            return thread;
        });

        try {
            List<Callable<ToolExecutionResult>> tasks = invocations.stream()
                    .<Callable<ToolExecutionResult>>map(invocation -> () -> executeTool(invocation))
                    .toList();

            List<Future<ToolExecutionResult>> futures =
                    executor.invokeAll(tasks, toolBatchTimeoutSeconds, TimeUnit.SECONDS);

            List<ToolExecutionResult> results = new ArrayList<>(invocations.size());
            for (int i = 0; i < futures.size(); i++) {
                ToolInvocation invocation = invocations.get(i);
                Future<ToolExecutionResult> future = futures.get(i);
                if (future.isCancelled()) {
                    results.add(ToolExecutionResult.timedOut(invocation, toolBatchTimeoutSeconds));
                    continue;
                }
                try {
                    results.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(ToolExecutionResult.failed(invocation, "工具执行被中断"));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    String message = cause == null || cause.getMessage() == null
                            ? "未知错误"
                            : cause.getMessage();
                    results.add(ToolExecutionResult.failed(invocation, message));
                }
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return invocations.stream()
                    .map(inv -> ToolExecutionResult.failed(inv, "工具批次执行被中断"))
                    .toList();
        } finally {
            executor.shutdownNow();
        }
    }

    protected ToolExecutionResult executeTool(ToolInvocation invocation) {
        return doExecuteTool(invocation);
    }

    protected ToolExecutionResult doExecuteTool(ToolInvocation invocation) {
        long startTime = System.currentTimeMillis();
        try {
            Tool tool = tools.get(invocation.name());
            if (tool == null) {
                return new ToolExecutionResult(
                        invocation.id(),
                        invocation.name(),
                        "错误: 未知工具 - " + invocation.name(),
                        System.currentTimeMillis() - startTime
                );
            }

            McpRegisteredTool mcpTool = mcpTools.get(invocation.name());
            if (mcpTool != null) {
                log.debug("执行 MCP 工具 {} with json args", invocation.name());
                String result = mcpTool.invoker().apply(invocation.argumentsJson());
                long elapsed = System.currentTimeMillis() - startTime;
                if (ApprovalPolicyHelper.shouldAudit(invocation.name())) {
                    auditLog.record(AuditLog.AuditEntry.allow(
                            invocation.name(), invocation.argumentsJson(), elapsed));
                }
                return new ToolExecutionResult(invocation.id(), invocation.name(), result, elapsed);
            }

            Map<String, String> args = parseArguments(invocation.argumentsJson());
            log.debug("执行工具 {} with args: {}", invocation.name(), args);
            String result = tool.executor().execute(args);
            long elapsed = System.currentTimeMillis() - startTime;
            if (ApprovalPolicyHelper.shouldAudit(invocation.name())) {
                auditLog.record(AuditLog.AuditEntry.allow(
                        invocation.name(), invocation.argumentsJson(), elapsed));
            }
            return new ToolExecutionResult(invocation.id(), invocation.name(), result, elapsed);
        } catch (PolicyException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            if (ApprovalPolicyHelper.shouldAudit(invocation.name())) {
                auditLog.record(AuditLog.AuditEntry.denyByPolicy(
                        invocation.name(), invocation.argumentsJson(), e.getMessage(), elapsed));
            }
            return new ToolExecutionResult(
                    invocation.id(),
                    invocation.name(),
                    "🛡️ 策略拒绝: " + e.getMessage(),
                    elapsed
            );
        } catch (Exception e) {
            log.error("工具执行异常: {}", invocation.name(), e);
            long elapsed = System.currentTimeMillis() - startTime;
            if (ApprovalPolicyHelper.shouldAudit(invocation.name())) {
                auditLog.record(AuditLog.AuditEntry.error(
                        invocation.name(), invocation.argumentsJson(), e.getMessage(), elapsed));
            }
            return new ToolExecutionResult(
                    invocation.id(),
                    invocation.name(),
                    "工具执行异常: " + e.getMessage(),
                    elapsed
            );
        }
    }

    public AuditLog getAuditLog() {
        return auditLog;
    }

    /**
     * 避免 ToolRegistry 依赖 hitl 包。
     */
    private static final class ApprovalPolicyHelper {
        private static boolean shouldAudit(String toolName) {
            return "write_file".equals(toolName)
                    || "execute_command".equals(toolName)
                    || "create_project".equals(toolName)
                    || (toolName != null && toolName.startsWith("mcp__"));
        }
    }

    protected Map<String, String> parseArguments(String argumentsJson) {
        Map<String, String> args = new HashMap<>();
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return args;
        }
        try {
            JsonNode node = LlmClient.MAPPER.readTree(argumentsJson);
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                args.put(entry.getKey(), entry.getValue().asText());
            }
        } catch (Exception e) {
            log.warn("解析工具参数失败: {}", argumentsJson, e);
        }
        return args;
    }

    public Path getWorkingDirectory() {
        return workingDirectory;
    }

    public String getProjectPath() {
        return workingDirectory.toString();
    }

    public PathGuard getPathGuard() {
        return pathGuard;
    }

    public void setProjectPath(Path newDirectory) {
        this.workingDirectory = newDirectory.toAbsolutePath().normalize();
        this.pathGuard = new PathGuard(workingDirectory.toString());
        this.fileTools = new FileTools(pathGuard);
        log.info("项目根已设置: {}", workingDirectory);
    }

    public void setWorkingDirectory(Path newDirectory) {
        setProjectPath(newDirectory);
    }

    public void setWorkingDirectory(String newDirectory) {
        setProjectPath(Paths.get(newDirectory));
    }

    private ObjectNode createParameters(Param... params) {
        ObjectNode schema = LlmClient.MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = LlmClient.MAPPER.createObjectNode();
        ArrayNode required = LlmClient.MAPPER.createArrayNode();
        for (Param param : params) {
            properties.set(param.name(), switch (param.type()) {
                case "integer" -> integerProperty(param.description());
                case "boolean" -> boolProperty(param.description());
                default -> stringProperty(param.description());
            });
            if (param.required()) {
                required.add(param.name());
            }
        }
        schema.set("properties", properties);
        if (!required.isEmpty()) {
            schema.set("required", required);
        }
        return schema;
    }

    private ObjectNode stringProperty(String description) {
        ObjectNode node = LlmClient.MAPPER.createObjectNode();
        node.put("type", "string");
        node.put("description", description);
        return node;
    }

    private ObjectNode integerProperty(String description) {
        ObjectNode node = LlmClient.MAPPER.createObjectNode();
        node.put("type", "integer");
        node.put("description", description);
        return node;
    }

    private ObjectNode boolProperty(String description) {
        ObjectNode node = LlmClient.MAPPER.createObjectNode();
        node.put("type", "boolean");
        node.put("description", description);
        return node;
    }

    private static int parseInt(String raw, int defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
