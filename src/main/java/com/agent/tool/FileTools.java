package com.agent.tool;

import com.agent.policy.PathGuard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 文件类工具实现：read / write / list / glob / grep。
 */
public class FileTools {

    private static final int MAX_READ_FILE_BYTES = 1024 * 1024;
    private static final int MAX_GREP_RESULTS = 200;
    private static final Set<String> SEARCH_EXCLUDED_DIRS = Set.of(
            ".git", "target", "node_modules", "dist", "build", "coverage", ".idea", ".gradle"
    );

    private final PathGuard pathGuard;

    public FileTools(PathGuard pathGuard) {
        this.pathGuard = pathGuard;
    }

    public String readFile(Map<String, String> args) throws IOException {
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

    public String writeFile(Map<String, String> args) throws IOException {
        Path filePath = pathGuard.resolveSafe(args.get("path"));
        Path parentDir = filePath.getParent();
        if (parentDir != null && !Files.exists(parentDir)) {
            Files.createDirectories(parentDir);
        }
        Files.writeString(filePath, args.get("content"), StandardCharsets.UTF_8);
        return "文件写入成功: " + pathGuard.getRootPath().relativize(filePath);
    }

    public String listDir(Map<String, String> args) throws IOException {
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

    public String globFiles(Map<String, String> args) throws IOException {
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

    public String grepCode(Map<String, String> args) throws IOException {
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
