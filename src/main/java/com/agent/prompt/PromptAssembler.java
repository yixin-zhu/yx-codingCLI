package com.agent.prompt;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;

/**
 * 按固定顺序组装 system prompt：base → mode → project/memory → runtime_context。
 */
public class PromptAssembler {

    private final PromptRepository repository;

    public PromptAssembler(PromptRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public static PromptAssembler createDefault() {
        return new PromptAssembler(PromptRepository.createDefault());
    }

    public String assemble(PromptMode mode, PromptContext context) {
        Objects.requireNonNull(mode, "mode");
        PromptContext ctx = context == null ? PromptContext.empty() : context;

        String base = repository.loadRequired("base.md");
        validateLanguageSection(base, "base.md");

        StringBuilder prompt = new StringBuilder();
        append(prompt, base);
        append(prompt, applyVariables(repository.loadRequired(mode.resourcePath()), ctx));
        append(prompt, dynamicSection("Project Context", ctx.projectMemoryContext()));
        append(prompt, ctx.memoryContext());
        append(prompt, runtimeContext(ctx));

        String assembled = prompt.toString().trim();
        validateLanguageSection(assembled, "assembled prompt");
        return assembled;
    }

    private static String runtimeContext(PromptContext context) {
        ZoneId zone = ZoneId.systemDefault();
        String workspace = context.variable("workspacePath");
        return "## Runtime Context\n\n"
                + "- 当前项目根: " + workspace + "\n"
                + "- 当前日期: " + LocalDate.now(zone) + "\n"
                + "- 当前时区: " + zone;
    }

    private static String applyVariables(String template, PromptContext context) {
        String result = template;
        for (Map.Entry<String, String> entry : context.variables().entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result;
    }

    private static String dynamicSection(String title, String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.contains("## ")) {
            return value.trim();
        }
        return "## " + title + "\n\n" + value.trim();
    }

    private static void append(StringBuilder sb, String section) {
        if (section == null || section.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append("\n\n");
        }
        sb.append(section.trim());
    }

    private static void validateLanguageSection(String prompt, String source) {
        if (prompt == null || !prompt.contains("## Language")) {
            throw new IllegalStateException("Prompt " + source + " must contain a '## Language' section");
        }
    }
}
