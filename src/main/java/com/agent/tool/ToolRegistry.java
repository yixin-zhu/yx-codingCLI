package com.agent.tool;

import com.agent.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
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
    private Path workingDirectory;

    // ==================== 内部数据结构 ====================

    /**
     * 工具定义
     */
    record Tool(String name, String description, JsonNode parameters, ToolExecutor executor) {}

    /**
     * 工具执行器接口
     */
    @FunctionalInterface
    interface ToolExecutor {
        String execute(Map<String, String> args) throws Exception;
    }

    /**
     * 工具调用请求
     */
    public record ToolInvocation(String id, String name, String argumentsJson) {}

    /**
     * 工具执行结果
     */
    public record ToolExecutionResult(String id, String name, String result, long elapsedMillis) {}

    // ==================== 构造函数 ====================

    public ToolRegistry() {
        this(Paths.get(System.getProperty("user.dir")));
    }

    public ToolRegistry(Path workingDirectory) {
        this.workingDirectory = workingDirectory.toAbsolutePath().normalize();
        registerDefaultTools();
    }

    // ==================== 工具注册 ====================

    /**
     * 注册默认工具
     */
    private void registerDefaultTools() {
        registerReadFile();
        registerWriteFile();
        registerListDir();
        registerExecuteCommand();
    }

    /**
     * 注册读文件工具
     */
    private void registerReadFile() {
        ObjectNode params = LlmClient.MAPPER.createObjectNode();
        params.put("type", "object");

        ObjectNode properties = LlmClient.MAPPER.createObjectNode();
        ObjectNode pathProp = LlmClient.MAPPER.createObjectNode();
        pathProp.put("type", "string");
        pathProp.put("description", "要读取的文件路径（相对或绝对）");
        properties.set("path", pathProp);

        params.set("properties", properties);
        params.putArray("required").add("path");

        registerTool(new Tool(
                "read_file",
                "读取指定文件的内容。适用于查看代码文件、配置文件等。",
                params,
                args -> {
                    String path = args.get("path");
                    Path filePath = resolvePath(path);

                    if (!Files.exists(filePath)) {
                        return "错误: 文件不存在 - " + path;
                    }
                    if (!Files.isRegularFile(filePath)) {
                        return "错误: 路径不是文件 - " + path;
                    }
                    if (!isPathWithinWorkspace(filePath)) {
                        return "错误: 路径超出工作目录范围 - " + path;
                    }

                    // 限制读取文件大小（1MB）
                    if (Files.size(filePath) > 1024 * 1024) {
                        return "错误: 文件过大（超过1MB），请使用 read_file_part 分块读取";
                    }

                    return Files.readString(filePath, StandardCharsets.UTF_8);
                }
        ));
    }

    /**
     * 注册写文件工具
     */
    private void registerWriteFile() {
        ObjectNode params = LlmClient.MAPPER.createObjectNode();
        params.put("type", "object");

        ObjectNode properties = LlmClient.MAPPER.createObjectNode();

        ObjectNode pathProp = LlmClient.MAPPER.createObjectNode();
        pathProp.put("type", "string");
        pathProp.put("description", "要写入的文件路径（相对或绝对）");
        properties.set("path", pathProp);

        ObjectNode contentProp = LlmClient.MAPPER.createObjectNode();
        contentProp.put("type", "string");
        contentProp.put("description", "要写入文件的内容");
        properties.set("content", contentProp);

        params.set("properties", properties);
        params.putArray("required").add("path").add("content");

        registerTool(new Tool(
                "write_file",
                "将内容写入指定文件。如果文件不存在会创建，如果存在会覆盖。",
                params,
                args -> {
                    String path = args.get("path");
                    String content = args.get("content");
                    Path filePath = resolvePath(path);

                    if (!isPathWithinWorkspace(filePath)) {
                        return "错误: 路径超出工作目录范围 - " + path;
                    }

                    // 创建父目录
                    Path parentDir = filePath.getParent();
                    if (parentDir != null && !Files.exists(parentDir)) {
                        Files.createDirectories(parentDir);
                    }

                    Files.writeString(filePath, content, StandardCharsets.UTF_8);
                    return "文件写入成功: " + filePath;
                }
        ));
    }

    /**
     * 注册列目录工具
     */
    private void registerListDir() {
        ObjectNode params = LlmClient.MAPPER.createObjectNode();
        params.put("type", "object");

        ObjectNode properties = LlmClient.MAPPER.createObjectNode();
        ObjectNode pathProp = LlmClient.MAPPER.createObjectNode();
        pathProp.put("type", "string");
        pathProp.put("description", "要列出的目录路径（默认为当前目录）");
        properties.set("path", pathProp);

        ObjectNode recursiveProp = LlmClient.MAPPER.createObjectNode();
        recursiveProp.put("type", "boolean");
        recursiveProp.put("description", "是否递归列出子目录（默认false）");
        properties.set("recursive", recursiveProp);

        params.set("properties", properties);

        registerTool(new Tool(
                "list_dir",
                "列出指定目录下的文件和子目录。",
                params,
                args -> {
                    String path = args.getOrDefault("path", ".");
                    boolean recursive = Boolean.parseBoolean(args.getOrDefault("recursive", "false"));
                    Path dirPath = resolvePath(path);

                    if (!Files.exists(dirPath)) {
                        return "错误: 目录不存在 - " + path;
                    }
                    if (!Files.isDirectory(dirPath)) {
                        return "错误: 路径不是目录 - " + path;
                    }
                    if (!isPathWithinWorkspace(dirPath)) {
                        return "错误: 路径超出工作目录范围 - " + path;
                    }

                    StringBuilder sb = new StringBuilder();
                    sb.append("目录列表: ").append(dirPath).append("\n");

                    if (recursive) {
                        Files.walk(dirPath, 3)
                                .filter(p -> !p.getFileName().toString().startsWith("."))
                                .sorted()
                                .forEach(p -> {
                                    String relativePath = dirPath.relativize(p).toString();
                                    String prefix = Files.isDirectory(p) ? "[DIR] " : "[FILE] ";
                                    sb.append(prefix).append(relativePath).append("\n");
                                });
                    } else {
                        try (var stream = Files.list(dirPath)) {
                            stream.filter(p -> !p.getFileName().toString().startsWith("."))
                                    .sorted()
                                    .forEach(p -> {
                                        String name = p.getFileName().toString();
                                        String prefix = Files.isDirectory(p) ? "[DIR]  " : "[FILE] ";
                                        String size = Files.isRegularFile(p) ? " (" + getFileSize(p) + ")" : "";
                                        sb.append(prefix).append(name).append(size).append("\n");
                                    });
                        }
                    }

                    return sb.toString();
                }
        ));
    }

    /**
     * 注册执行命令工具
     */
    private void registerExecuteCommand() {
        ObjectNode params = LlmClient.MAPPER.createObjectNode();
        params.put("type", "object");

        ObjectNode properties = LlmClient.MAPPER.createObjectNode();
        ObjectNode commandProp = LlmClient.MAPPER.createObjectNode();
        commandProp.put("type", "string");
        commandProp.put("description", "要执行的 shell 命令");
        properties.set("command", commandProp);

        ObjectNode timeoutProp = LlmClient.MAPPER.createObjectNode();
        timeoutProp.put("type", "integer");
        timeoutProp.put("description", "命令超时时间（秒，默认30秒）");
        properties.set("timeout", timeoutProp);

        params.set("properties", properties);
        params.putArray("required").add("command");

        registerTool(new Tool(
                "execute_command",
                "在终端中执行 shell 命令并返回输出结果。",
                params,
                args -> {
                    String command = args.get("command");
                    int timeout = Integer.parseInt(args.getOrDefault("timeout", "30"));

                    log.info("执行命令: {}", command);

                    // 根据操作系统选择命令执行方式
                    String os = System.getProperty("os.name").toLowerCase();
                    ProcessBuilder pb;
                    if (os.contains("win")) {
                        pb = new ProcessBuilder("cmd", "/c", command);
                    } else {
                        pb = new ProcessBuilder("bash", "-c", command);
                    }

                    pb.directory(workingDirectory.toFile());
                    pb.redirectErrorStream(true);
                    pb.environment().put("JAVA_TOOL_OPTIONS", "-Dfile.encoding=UTF-8");

                    Process process = pb.start();

                    // 读取输出
                    StringBuilder output = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            output.append(line).append("\n");
                        }
                    }

                    // 等待进程完成
                    boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
                    if (!finished) {
                        process.destroyForcibly();
                        return "错误: 命令执行超时（" + timeout + "秒）\n已输出: " + output;
                    }

                    int exitCode = process.exitValue();
                    if (exitCode != 0) {
                        return "命令执行失败 (exit code: " + exitCode + ")\n输出:\n" + output;
                    }

                    return output.toString();
                }
        ));
    }

    /**
     * 注册自定义工具
     */
    public void registerTool(Tool tool) {
        tools.put(tool.name(), tool);
        log.debug("注册工具: {}", tool.name());
    }

    // ==================== 工具查询 ====================

    /**
     * 获取所有工具的 LLM 定义
     */
    public List<LlmClient.Tool> getToolDefinitions() {
        return tools.values().stream()
                .map(tool -> new LlmClient.Tool(tool.name(), tool.description(), tool.parameters()))
                .collect(Collectors.toList());
    }

    /**
     * 根据名称获取工具
     */
    public Optional<Tool> getTool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * 获取所有已注册的工具名称
     */
    public Set<String> getToolNames() {
        return tools.keySet();
    }

    // ==================== 工具执行 ====================

    /**
     * 执行工具调用列表（顺序执行）
     */
    public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations) {
        List<ToolExecutionResult> results = new ArrayList<>();

        for (ToolInvocation invocation : invocations) {
            ToolExecutionResult result = executeTool(invocation);
            results.add(result);
        }

        return results;
    }

    /**
     * 执行单个工具调用
     */
    private ToolExecutionResult executeTool(ToolInvocation invocation) {
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

            // 解析参数
            Map<String, String> args = parseArguments(invocation.argumentsJson());
            log.debug("执行工具 {} with args: {}", invocation.name(), args);

            // 执行
            String result = tool.executor().execute(args);
            long elapsed = System.currentTimeMillis() - startTime;

            log.debug("工具 {} 执行完成，耗时 {}ms", invocation.name(), elapsed);

            return new ToolExecutionResult(invocation.id(), invocation.name(), result, elapsed);

        } catch (Exception e) {
            log.error("工具执行异常: {}", invocation.name(), e);
            return new ToolExecutionResult(
                    invocation.id(),
                    invocation.name(),
                    "工具执行异常: " + e.getMessage(),
                    System.currentTimeMillis() - startTime
            );
        }
    }

    /**
     * 解析工具参数 JSON
     */
    private Map<String, String> parseArguments(String argumentsJson) {
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

    // ==================== 路径工具 ====================

    /**
     * 获取工作目录
     */
    public Path getWorkingDirectory() {
        return workingDirectory;
    }

    /**
     * 获取项目路径字符串（与 PaiCLI 的 getProjectPath 对应）
     */
    public String getProjectPath() {
        return workingDirectory.toString();
    }

    /**
     * 设置工作目录
     */
    public void setWorkingDirectory(Path newDirectory) {
        this.workingDirectory = newDirectory.toAbsolutePath().normalize();
        log.info("工作目录已切换: {}", workingDirectory);
    }

    /**
     * 通过字符串设置工作目录
     */
    public void setWorkingDirectory(String newDirectory) {
        setWorkingDirectory(Paths.get(newDirectory));
    }

    /**
     * 解析路径（支持相对路径和绝对路径）
     */
    private Path resolvePath(String path) {
        Path p = Paths.get(path);
        if (p.isAbsolute()) {
            return p.normalize();
        }
        return workingDirectory.resolve(p).normalize();
    }

    /**
     * 检查路径是否在工作目录内（安全限制）
     */
    private boolean isPathWithinWorkspace(Path path) {
        try {
            Path normalizedPath = path.toAbsolutePath().normalize();
            return normalizedPath.startsWith(workingDirectory);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取文件大小的可读字符串
     */
    private String getFileSize(Path path) {
        try {
            long size = Files.size(path);
            if (size < 1024) {
                return size + " B";
            } else if (size < 1024 * 1024) {
                return String.format("%.1f KB", size / 1024.0);
            } else {
                return String.format("%.1f MB", size / (1024.0 * 1024));
            }
        } catch (IOException e) {
            return "?";
        }
    }
}
