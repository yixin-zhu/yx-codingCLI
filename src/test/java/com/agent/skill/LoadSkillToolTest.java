package com.agent.skill;

import com.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadSkillToolTest {

    @Test
    void loadsExistingSkillIntoBuffer(@TempDir Path tempDir) throws IOException {
        SkillRegistry registry = registryWith(tempDir, "web-access", "guide", "# Body\nwhen to fetch\n");
        SkillContextBuffer buffer = new SkillContextBuffer();
        ToolRegistry tools = new ToolRegistry();
        tools.setSkillRegistry(registry);
        tools.setSkillContextBuffer(buffer);

        String result = tools.executeTool("load_skill", "{\"name\":\"web-access\"}");

        assertTrue(result.contains("web-access"), result);
        assertFalse(buffer.isEmpty());
        assertTrue(buffer.drain().contains("when to fetch"));
    }

    @Test
    void failsForUnknownSkill(@TempDir Path tempDir) throws IOException {
        SkillRegistry registry = registryWith(tempDir, "real-one", "desc", "body");
        ToolRegistry tools = new ToolRegistry();
        tools.setSkillRegistry(registry);
        tools.setSkillContextBuffer(new SkillContextBuffer());

        String result = tools.executeTool("load_skill", "{\"name\":\"nonexistent\"}");
        assertTrue(result.contains("nonexistent"), result);
    }

    @Test
    void failsForDisabledSkill(@TempDir Path tempDir) throws IOException {
        SkillStateStore state = new SkillStateStore(tempDir.resolve("skills.json"));
        state.disable("web-access");

        SkillRegistry registry = new SkillRegistry(null,
                writeUserSkill(tempDir, "web-access", "desc", "body").getParent().getParent(),
                null, state);
        registry.reload();

        SkillContextBuffer buffer = new SkillContextBuffer();
        ToolRegistry tools = new ToolRegistry();
        tools.setSkillRegistry(registry);
        tools.setSkillContextBuffer(buffer);

        String result = tools.executeTool("load_skill", "{\"name\":\"web-access\"}");
        assertTrue(result.contains("/skill on"), result);
        assertTrue(buffer.isEmpty());
    }

    @Test
    void failsWhenNameMissing() {
        ToolRegistry tools = new ToolRegistry();
        tools.setSkillRegistry(new SkillRegistry(null, null, null, null));
        tools.setSkillContextBuffer(new SkillContextBuffer());

        String result = tools.executeTool("load_skill", "{}");
        assertTrue(result.toLowerCase().contains("name"), result);
    }

    private static SkillRegistry registryWith(Path tempDir, String name, String desc, String body) throws IOException {
        Path userRoot = writeUserSkill(tempDir, name, desc, body).getParent().getParent();
        SkillStateStore state = new SkillStateStore(tempDir.resolve("skills.json"));
        SkillRegistry registry = new SkillRegistry(null, userRoot, null, state);
        registry.reload();
        return registry;
    }

    private static Path writeUserSkill(Path tempDir, String name, String desc, String body) throws IOException {
        Path userRoot = tempDir.resolve("user-skills");
        Path skillDir = userRoot.resolve(name);
        Files.createDirectories(skillDir);
        Path skillMd = skillDir.resolve("SKILL.md");
        Files.writeString(skillMd,
                "---\nname: " + name
                        + "\ndescription: " + desc
                        + "\n---\n" + body + "\n");
        return skillMd;
    }
}
