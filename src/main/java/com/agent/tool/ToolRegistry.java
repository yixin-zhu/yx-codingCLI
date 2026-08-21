package com.agent.tool;

import com.agent.llm.LlmClient;
import com.agent.policy.PathGuard;
import com.agent.policy.PolicyException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具注册表 - 管理所有可用工具
 */
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);
    private static final int MAX_READ_FILE_BYTES = 1024 * 1024;
    private static final int MAX_GREP_RESULTS = 200;
    private static final Set<String> SEARCH_EXCLUDED_DIRS = Set.of(
            ".git", "target", "node_modules", "dist", "build", "coverage", ".idea", ".gradle"
    );

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private Path workingDirectory;
    private PathGuard pathGuard;

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
    }

    private void registerReadFile() {
        registerTool(new Tool(
                "read_file",
                "读取指定文件的内容。适用于查看代码文件、配置文件等。",
                createParameters(new Param("path", "string", "要读取的文件路径（相对项目根）", true)),
                args -> {
                    Path filePath = pathGuard.resolveSafe(args.get("path"));
                    if (!Files.exists(filePath)) {
                        return "错误: 文件不存在 - " + args.get("path");
                    }
                    if (!Files.isRegularFile(filePath)) {
                        return "错误: 路径不是文件 - " + args.get("path");
                    }
                    if (Files.size(filePath) > MAX_READ_FILE_BYTES) {
                        return "错误: 文件过大（超过1MB）";
                    }
                    return Files.readString(filePath, StandardCharsets.UTF_8);
                }
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
                args -> {
                    Path filePath = pathGuard.resolveSafe(args.get("path"));
                    Path parentDir = filePath.getParent();
                    if (parentDir != null && !Files.exists(parentDir)) {
                        Files.createDirectories(parentDir);
                    }
                    Files.writeString(filePath, args.get("content"), StandardCharsets.UTF_8);
                    return "文件写入成功: " + pathGuard.getRootPath().relativize(filePath);
                }
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
                args -> {
                    Path dirPath = pathGuard.resolveSafe(args.getOrDefault("path", "."));
                    if (!Files.exists(dirPath)) {
                        return "错误: 目录不存在 - " + args.getOrDefault("path", ".");
                    }
                    if (!Files.isDirectory(dirPath)) {
                        return "错误: 路径不是目录 - " + args.getOrDefault("path", ".");
                    }

                    boolean recursive = Boolean.parseBoolean(args.getOrDefault("recursive", "false"));
                    StringBuilder sb = new StringBuilder();
                    sb.append("目录列表: ").append(pathGuard.getRootPath().relativize(dirPath)).append("\n");

                    if (recursive) {
                        Files.walk(dirPath, 3)
                                .filter(p -> !isHiddenEntry(p))
                                .sorted()
                                .forEach(p -> {
                                    String relativePath = dirPath.relativize(p).toString();
                                    String prefix = Files.isDirectory(p) ? "[DIR] " : "[FILE] ";
                                    sb.append(prefix).append(relativePath).append("\n");
                                });
                    } else {
                        try (var stream = Files.list(dirPath)) {
                            stream.filter(p -> !isHiddenEntry(p))
                                    .sorted()
                                    .forEach(p -> {
                                        String name = p.getFileName().toString();
                                        String prefix = Files.isDirectory(p) ? "[DIR]  " : "[FILE] ";
                                        String size = Files.isRegularFile(p) ? " (" + formatFileSize(p) + ")" : "";
                                        sb.append(prefix).append(name).append(size).append("\n");
                                    });
                        }
                    }
                    return sb.toString();
                }
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
                args -> globFiles(args)
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
                args -> grepCode(args)
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

    private String globFiles(Map<String, String> args) throws IOException {
        String pattern = args.get("pattern");
        if (pattern == null || pattern.isBlank()) {
            return "文件匹配失败: pattern 不能为空";
        }

        Path root = pathGuard.resolveSafe(args.getOrDefault("path", "."));
        int maxResults = clamp(parseInt(args.get("max_results"), 50), 1, MAX_GREP_RESULTS);
        Path projectRoot = pathGuard.getRootPath();
        PathMatcher matcher = projectRoot.getFileSystem().getPathMatcher("glob:" + normalizeGlob(pattern));
        List<String> matches = new ArrayList<>();

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (shouldSkipDirectory(dir, projectRoot)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (matches.size() >= maxResults) {
                    return FileVisitResult.TERMINATE;
                }
                Path relative = projectRoot.relativize(file.toAbsolutePath().normalize());
                if (matcher.matches(relative) || matcher.matches(file.getFileName())) {
                    matches.add(relative.toString().replace('\\', '/'));
                }
                return FileVisitResult.CONTINUE;
            }
        });

        if (matches.isEmpty()) {
            return "未找到匹配文件: " + pattern;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("匹配文件 ").append(matches.size()).append(" 个");
        if (matches.size() >= maxResults) {
            sb.append("（已达到上限 ").append(maxResults).append("）");
        }
        sb.append(":\n");
        for (int i = 0; i < matches.size(); i++) {
            sb.append(i + 1).append(". ").append(matches.get(i)).append("\n");
        }
        return sb.toString().trim();
    }

    private String grepCode(Map<String, String> args) throws IOException {
        String query = args.get("pattern");
        if (query == null || query.isBlank()) {
            return "代码搜索失败: pattern 不能为空";
        }

        Path root = pathGuard.resolveSafe(args.getOrDefault("path", "."));
        Path projectRoot = pathGuard.getRootPath();
        int maxResults = clamp(parseInt(args.get("max_results"), 50), 1, MAX_GREP_RESULTS);
        String globFilter = args.get("glob");
        PathMatcher globMatcher = globFilter == null || globFilter.isBlank()
                ? null
                : projectRoot.getFileSystem().getPathMatcher("glob:" + normalizeGlob(globFilter));

        List<String> hits = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (shouldSkipDirectory(dir, projectRoot)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (hits.size() >= maxResults || !Files.isRegularFile(file)) {
                    return hits.size() >= maxResults ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }
                Path relative = projectRoot.relativize(file.toAbsolutePath().normalize());
                if (globMatcher != null && !globMatcher.matches(relative) && !globMatcher.matches(file.getFileName())) {
                    return FileVisitResult.CONTINUE;
                }
                if (isBinaryLikely(file)) {
                    return FileVisitResult.CONTINUE;
                }

                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (hits.size() >= maxResults) {
                        break;
                    }
                    if (lines.get(i).contains(query)) {
                        hits.add(relative.toString().replace('\\', '/')
                                + ":" + (i + 1) + ": " + lines.get(i).trim());
                    }
                }
                return hits.size() >= maxResults ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
        });

        if (hits.isEmpty()) {
            return "未找到匹配内容: " + query;
        }
        StringBuilder sb = new StringBuilder("匹配结果 ").append(hits.size()).append(" 条:\n");
        for (int i = 0; i < hits.size(); i++) {
            sb.append(i + 1).append(". ").append(hits.get(i)).append("\n");
        }
        return sb.toString().trim();
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
            Map<String, String> args = parseArguments(invocation.argumentsJson());
            log.debug("执行工具 {} with args: {}", invocation.name(), args);
            String result = tool.executor().execute(args);
            return new ToolExecutionResult(
                    invocation.id(), invocation.name(), result, System.currentTimeMillis() - startTime);
        } catch (PolicyException e) {
            return new ToolExecutionResult(
                    invocation.id(),
                    invocation.name(),
                    "🛡️ 策略拒绝: " + e.getMessage(),
                    System.currentTimeMillis() - startTime
            );
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

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String normalizeGlob(String pattern) {
        String normalized = pattern.replace('\\', '/');
        if (!normalized.startsWith("**/") && !normalized.startsWith("/")) {
            normalized = "**/" + normalized;
        }
        return normalized;
    }

    private static boolean shouldSkipDirectory(Path dir, Path projectRoot) {
        if (dir.equals(projectRoot)) {
            return false;
        }
        String name = dir.getFileName().toString();
        return name.startsWith(".") || SEARCH_EXCLUDED_DIRS.contains(name);
    }

    private static boolean isHiddenEntry(Path path) {
        String name = path.getFileName().toString();
        return name.startsWith(".");
    }

    private static boolean isBinaryLikely(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jar") || name.endsWith(".class") || name.endsWith(".png")
                || name.endsWith(".jpg") || name.endsWith(".zip");
    }

    private static String formatFileSize(Path path) {
        try {
            long size = Files.size(path);
            if (size < 1024) {
                return size + " B";
            }
            if (size < 1024 * 1024) {
                return String.format(Locale.ROOT, "%.1f KB", size / 1024.0);
            }
            return String.format(Locale.ROOT, "%.1f MB", size / (1024.0 * 1024));
        } catch (IOException e) {
            return "?";
        }
    }
}
