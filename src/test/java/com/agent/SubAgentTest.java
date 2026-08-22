package com.agent;

import com.agent.llm.LlmClient;
import com.agent.tool.ToolRegistry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubAgentTest {

    @Test
    void shouldOnlyEnableToolsForWorker() {
        LlmClient llmClient = (messages, tools) -> new LlmClient.ChatResponse("assistant", "ok", null, 0, 0);

        SubAgent planner = new SubAgent("planner", AgentRole.PLANNER, llmClient, new ToolRegistry());
        SubAgent worker = new SubAgent("worker", AgentRole.WORKER, llmClient, new ToolRegistry());
        SubAgent reviewer = new SubAgent("reviewer", AgentRole.REVIEWER, llmClient, new ToolRegistry());

        assertFalse(planner.shouldUseTools());
        assertTrue(worker.shouldUseTools());
        assertFalse(reviewer.shouldUseTools());
    }
}
