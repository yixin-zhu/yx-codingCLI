package com.agent.policy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 路径围栏：文件类工具调用必须先经过它，确保路径留在项目根之内。
 */
public class PathGuard {

    private final Path rootPath;

    public PathGuard(String root) {
        if (root == null || root.isBlank()) {
            throw new IllegalArgumentException("项目根路径不能为空");
        }
        Path candidate = Paths.get(root).toAbsolutePath().normalize();
        Path real = candidate;
        try {
            if (Files.exists(candidate)) {
                real = candidate.toRealPath();
            }
        } catch (IOException ignored) {
        }
        this.rootPath = real;
    }

    public Path getRootPath() {
        return rootPath;
    }

    public Path resolveSafe(String input) {
        if (input == null || input.isBlank()) {
            throw new PolicyException("路径不能为空");
        }

        Path raw = Paths.get(input);
        Path resolved = raw.isAbsolute()
                ? raw.normalize()
                : rootPath.resolve(raw).normalize();

        Path realResolved = resolveRealPath(resolved);
        if (!realResolved.startsWith(rootPath)) {
            throw new PolicyException("路径越界: " + input + " 不在项目根 " + rootPath + " 之内");
        }
        return realResolved;
    }

    private Path resolveRealPath(Path target) {
        Path existing = target;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return target.toAbsolutePath().normalize();
        }
        try {
            Path realExisting = existing.toRealPath();
            Path remainder = existing.relativize(target);
            return realExisting.resolve(remainder).normalize();
        } catch (IOException e) {
            return target.toAbsolutePath().normalize();
        }
    }
}
