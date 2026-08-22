package com.agent.skill;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 把 jar 内 resources/skills/&lt;name&gt;/ 解压到 ~/.agent/skills-cache/&lt;name&gt;/。
 */
public final class SkillBuiltinExtractor {

    public static final String CURRENT_VERSION = "1.0.0";

    private static final List<BuiltinSkillSpec> BUILTIN_SKILLS = List.of(
            new BuiltinSkillSpec("web-access", List.of("SKILL.md"))
    );

    private final Path cacheRoot;

    public SkillBuiltinExtractor(Path cacheRoot) {
        this.cacheRoot = cacheRoot;
    }

    public Path cacheRoot() {
        return cacheRoot;
    }

    public void extractAll() throws IOException {
        Files.createDirectories(cacheRoot);
        for (BuiltinSkillSpec spec : BUILTIN_SKILLS) {
            extract(spec);
        }
    }

    private void extract(BuiltinSkillSpec spec) throws IOException {
        Path skillDir = cacheRoot.resolve(spec.name());
        Path versionFile = skillDir.resolve(".version");
        if (Files.exists(versionFile)) {
            String existing = Files.readString(versionFile).trim();
            if (CURRENT_VERSION.equals(existing)) {
                return;
            }
        }
        if (Files.exists(skillDir)) {
            deleteRecursive(skillDir);
        }
        Files.createDirectories(skillDir);
        for (String relative : spec.files()) {
            String resourcePath = "skills/" + spec.name() + "/" + relative;
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                if (in == null) {
                    System.err.println("⚠️ 内置 skill 资源缺失: " + resourcePath);
                    continue;
                }
                Path target = skillDir.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.writeString(versionFile, CURRENT_VERSION);
    }

    private static void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    private record BuiltinSkillSpec(String name, List<String> files) {
    }
}
