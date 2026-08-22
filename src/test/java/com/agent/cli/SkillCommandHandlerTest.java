package com.agent.cli;

import com.agent.skill.SkillRegistry;
import com.agent.skill.SkillStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillCommandHandlerTest {

    @Test
    void startupSummaryOnlyShowsCounts(@TempDir Path tempDir) throws IOException {
        SkillRegistry registry = registryWith(tempDir,
                new SkillSpec("web-access", "web guide", "1.0.0"),
                new SkillSpec("debug-helper", "debug", "0.1.0"));

        String out = SkillCommandHandler.startupSummary(registry);

        assertTrue(out.contains("2/2"));
        assertFalse(out.contains("web-access"));
    }

    @Test
    void listShowsAllSkillsWithStatus(@TempDir Path tempDir) throws IOException {
        SkillRegistry registry = registryWith(tempDir,
                new SkillSpec("web-access", "web guide", "1.0.0"),
                new SkillSpec("debug-helper", "debug", "0.1.0"));
        String out = SkillCommandHandler.list(registry);

        assertTrue(out.contains("web-access"));
        assertTrue(out.contains("debug-helper"));
        assertTrue(out.contains("/skill show"));
    }

    @Test
    void showReturnsErrorForMissingSkill(@TempDir Path tempDir) throws IOException {
        SkillRegistry registry = registryWith(tempDir);
        String out = SkillCommandHandler.show(registry, "nonexistent");
        assertTrue(out.contains("nonexistent"));
    }

    @Test
    void enableAndDisableUpdateStateStore(@TempDir Path tempDir) throws IOException {
        SkillStateStore state = new SkillStateStore(tempDir.resolve("skills.json"));
        SkillRegistry registry = registryWith(tempDir,
                new SkillSpec("web-access", "web guide", "1.0.0"));

        SkillCommandHandler.disable(registry, state, "web-access");
        assertTrue(state.disabled().contains("web-access"));

        SkillCommandHandler.enable(registry, state, "web-access");
        assertFalse(state.disabled().contains("web-access"));
    }

    private record SkillSpec(String name, String desc, String version) {
    }

    private static SkillRegistry registryWith(Path tempDir, SkillSpec... specs) throws IOException {
        Path userRoot = tempDir.resolve("user-skills");
        Files.createDirectories(userRoot);
        for (SkillSpec spec : specs) {
            Path dir = userRoot.resolve(spec.name());
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("SKILL.md"),
                    "---\nname: " + spec.name()
                            + "\ndescription: " + spec.desc()
                            + "\nversion: \"" + spec.version() + "\"\n---\nbody for " + spec.name() + "\n");
        }
        SkillStateStore state = new SkillStateStore(tempDir.resolve("skills.json"));
        SkillRegistry registry = new SkillRegistry(null, userRoot, null, state);
        registry.reload();
        return registry;
    }
}
