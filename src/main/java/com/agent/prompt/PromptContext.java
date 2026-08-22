package com.agent.prompt;

import java.util.LinkedHashMap;
import java.util.Map;

public record PromptContext(
        Map<String, String> variables,
        String projectMemoryContext,
        String memoryContext,
        String skillIndex
) {

    public static Builder builder() {
        return new Builder();
    }

    public static PromptContext empty() {
        return builder().build();
    }

    public String variable(String key) {
        if (variables == null || key == null) {
            return "";
        }
        return variables.getOrDefault(key, "");
    }

    public static final class Builder {
        private final Map<String, String> variables = new LinkedHashMap<>();
        private String projectMemoryContext = "";
        private String memoryContext = "";
        private String skillIndex = "";

        public Builder variable(String key, Object value) {
            if (key != null && !key.isBlank() && value != null) {
                this.variables.put(key.trim(), String.valueOf(value));
            }
            return this;
        }

        public Builder workspacePath(String workspacePath) {
            return variable("workspacePath", workspacePath);
        }

        public Builder projectMemoryContext(String projectMemoryContext) {
            this.projectMemoryContext = normalize(projectMemoryContext);
            return this;
        }

        public Builder memoryContext(String memoryContext) {
            this.memoryContext = normalize(memoryContext);
            return this;
        }

        public Builder skillIndex(String skillIndex) {
            this.skillIndex = normalize(skillIndex);
            return this;
        }

        public PromptContext build() {
            return new PromptContext(Map.copyOf(variables), projectMemoryContext, memoryContext, skillIndex);
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
