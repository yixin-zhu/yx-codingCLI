package com.agent.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptAssemblerTest {

    @Test
    void assemblesBuiltinAgentPromptWithRuntimeContext() {
        PromptAssembler assembler = PromptAssembler.createDefault();
        String workspace = "/tmp/demo-workspace";

        String prompt = assembler.assemble(PromptMode.AGENT, PromptContext.builder()
                .workspacePath(workspace)
                .build());

        assertTrue(prompt.contains("## Language"));
        assertTrue(prompt.contains("## Mode: ReAct Agent"));
        assertTrue(prompt.contains("## Runtime Context"));
        assertTrue(prompt.contains(workspace));
        assertTrue(prompt.contains("当前日期"));
        assertTrue(prompt.contains("当前时区"));
        assertTrue(prompt.contains("glob_files"));
        assertTrue(prompt.contains("grep_code"));
        assertTrue(prompt.contains("ReAct 模式下工作"));
        assertTrue(prompt.indexOf("## Language") < prompt.indexOf("## Mode: ReAct Agent"));
        assertTrue(prompt.indexOf("## Mode: ReAct Agent") < prompt.indexOf("## Runtime Context"));
    }

    @Test
    void rejectsPromptWithoutLanguageSection() {
        PromptAssembler assembler = new PromptAssembler(new PromptRepository() {
            @Override
            public String loadRequired(String relativePath) {
                if ("base.md".equals(relativePath)) {
                    return "## Identity\n\nno language section";
                }
                return "## Mode\n\nplaceholder";
            }
        });

        assertThrows(IllegalStateException.class, () ->
                assembler.assemble(PromptMode.AGENT, PromptContext.builder().workspacePath(".").build()));
    }
}
