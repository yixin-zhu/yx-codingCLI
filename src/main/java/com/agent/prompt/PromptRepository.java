package com.agent.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 从 classpath 加载内置 prompt 资源（Phase 1.5：不含用户/项目级覆盖）。
 */
public class PromptRepository {

    private static final String RESOURCE_PREFIX = "prompts/";

    private final ClassLoader classLoader;

    public PromptRepository() {
        this(PromptRepository.class.getClassLoader());
    }

    PromptRepository(ClassLoader classLoader) {
        this.classLoader = Objects.requireNonNull(classLoader);
    }

    public static PromptRepository createDefault() {
        return new PromptRepository();
    }

    public String loadRequired(String relativePath) {
        String normalized = normalize(relativePath);
        String content = loadBuiltin(normalized);
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("Prompt resource missing: " + normalized);
        }
        return content.trim();
    }

    private String loadBuiltin(String relativePath) {
        try (InputStream in = classLoader.getResourceAsStream(RESOURCE_PREFIX + relativePath)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read prompt resource: " + relativePath, e);
        }
    }

    private static String normalize(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("relativePath is blank");
        }
        String normalized = relativePath.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("..")) {
            throw new IllegalArgumentException("Invalid prompt path: " + relativePath);
        }
        return normalized;
    }
}
