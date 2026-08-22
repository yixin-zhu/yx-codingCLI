package com.agent.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillRegistryTest {

    @Test
    void loadsSkillsFromAllThreeLayers(@TempDir Path tempDir) throws IOException {
        Path builtin = tempDir.resolve("builtin");
        Path user = tempDir.resolve("user");
        Path project = tempDir.resolve("project");
        writeSkill(builtin, "web-access", "builtin desc", "v0");
        writeSkill(user, "user-only", "u desc", "v1");
        writeSkill(project, "project-only", "p desc", "v2");

        SkillStateStore state = new SkillStateStore(tempDir.resolve("skills.json"));
        SkillRegistry registry = new SkillRegistry(builtin, user, project, state);
        registry.reload();

        assertEquals(3, registry.allSkills().size());
    }

    @Test
    void projectOverridesBuiltin(@TempDir Path tempDir) throws IOException {
        Path builtin = tempDir.resolve("builtin");
        Path project = tempDir.resolve("project");
        writeSkill(builtin, "web-access", "builtin desc", "v-builtin");
        writeSkill(project, "web-access", "project desc", "v-project");

        SkillRegistry registry = new SkillRegistry(builtin, null, project, null);
        registry.reload();

        Skill skill = registry.allSkills().get(0);
        assertEquals("v-project", skill.version());
        assertEquals(Skill.Source.PROJECT, skill.source());
    }

    @Test
    void disabledFiltersOutSkill(@TempDir Path tempDir) throws IOException {
        Path builtin = tempDir.resolve("builtin");
        writeSkill(builtin, "web-access", "desc", "v0");
        writeSkill(builtin, "other", "desc2", "v0");

        SkillStateStore state = new SkillStateStore(tempDir.resolve("skills.json"));
        state.disable("other");

        SkillRegistry registry = new SkillRegistry(builtin, null, null, state);
        registry.reload();

        assertEquals(2, registry.allSkills().size());
        assertEquals(1, registry.enabledSkills().size());
        assertNull(registry.findSkill("other"));
    }

    private static void writeSkill(Path root, String name, String desc, String version) throws IOException {
        Path skillDir = root.resolve(name);
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"),
                "---\nname: " + name
                        + "\ndescription: " + desc
                        + "\nversion: \"" + version + "\"\n---\nbody for " + name + "\n");
    }
}
