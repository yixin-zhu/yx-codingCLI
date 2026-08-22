package com.agent.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AgentMdLoader {

    private AgentMdLoader() {
    }

    public static String loadForPrompt(Path workspace) {
        if (workspace == null) {
            return "";
        }
        Path agentMd = workspace.resolve("AGENT.md");
        if (!Files.isRegularFile(agentMd)) {
            return "";
        }
        try {
            String content = Files.readString(agentMd, StandardCharsets.UTF_8).trim();
            if (content.isBlank()) {
                return "";
            }
            return "## AGENT.md 项目记忆\n\n" + content;
        } catch (IOException e) {
            return "";
        }
    }
}
