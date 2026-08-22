package com.agent.tool;

import com.agent.llm.LlmClient;
import com.agent.policy.AuditLog;
import com.agent.policy.CommandGuard;
import com.agent.policy.PathGuard;
import com.agent.policy.PolicyException;
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
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具注册表 - 管理所有可用工具
 */
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final AuditLog auditLog = new AuditLog();
    private Path workingDirectory;
    private PathGuard pathGuard;
    private FileTools fileTools;
    private BiConsumer<String, String> memorySaver;

    record Tool(String name, String description, JsonNode parameters, ToolExecutor executor) {}

    @FunctionalInterface
    interface ToolExecutor {
        String execute(Map<String, String> args) throws Exception;
    }

    public record ToolInvocation(String id, String name, String argumentsJson) {}

    public record ToolExecutionResult(String id, String name, String result, long elapsedMillis) {}

    private record Param(String name, String type, String description, boolean required) {}

    public ToolRegistry() {
        this(Paths.get(System.getProperty("user.dir")));
    }

    public ToolRegistry(Path workingDirectory) {
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

    public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations) {
        List<ToolExecutionResult> results = new ArrayList<>();
        for (ToolInvocation invocation : invocations) {
            results.add(executeTool(invocation));
        }
        return results;
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
                    || "create_project".equals(toolName);
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
