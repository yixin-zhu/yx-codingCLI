package com.agent.skill;

import java.nio.file.Path;
import java.util.List;

public record Skill(
        String name,
        String description,
        String version,
        String author,
        List<String> tags,
        Source source,
        String body,
        Path skillMdPath,
        Path referencesDir
) {

    public enum Source {
        BUILTIN, USER, PROJECT
    }

    public Skill {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Skill name 不能为空");
        }
        if (description == null) {
            description = "";
        }
        if (tags == null) {
            tags = List.of();
        } else {
            tags = List.copyOf(tags);
        }
        if (body == null) {
            body = "";
        }
    }

    public String displaySource() {
        return switch (source) {
            case BUILTIN -> "builtin";
            case USER -> "user";
            case PROJECT -> "project";
        };
    }
}
