package com.agent.skill;

import java.nio.file.Path;

public final class SkillPaths {

    private SkillPaths() {
    }

    public static Path skillsCacheDir() {
        return Path.of(System.getProperty("user.home"), ".agent", "skills-cache");
    }

    public static Path userSkillsDir() {
        return Path.of(System.getProperty("user.home"), ".agent", "skills");
    }

    public static Path skillsStateFile() {
        return Path.of(System.getProperty("user.home"), ".agent", "skills.json");
    }

    public static Path projectSkillsDir(Path workspace) {
        return workspace.resolve(".agent").resolve("skills");
    }
}
