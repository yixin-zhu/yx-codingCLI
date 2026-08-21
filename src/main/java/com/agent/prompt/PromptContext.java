package com.agent.prompt;

import java.util.LinkedHashMap;
import java.util.Map;

public record PromptContext(Map<String, String> variables) {

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

        public Builder variable(String key, Object value) {
            if (key != null && !key.isBlank() && value != null) {
                this.variables.put(key.trim(), String.valueOf(value));
            }
            return this;
        }

        public Builder workspacePath(String workspacePath) {
            return variable("workspacePath", workspacePath);
        }

        public PromptContext build() {
            return new PromptContext(Map.copyOf(variables));
        }
    }
}
