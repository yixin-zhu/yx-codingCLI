package com.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentMessageTest {

    @Test
    void shouldCreateTypedMessages() {
        assertEquals(AgentMessage.Type.TASK, AgentMessage.task("orchestrator", "do").type());
        assertEquals(AgentMessage.Type.RESULT, AgentMessage.result("worker", AgentRole.WORKER, "ok").type());
        assertEquals(AgentMessage.Type.FEEDBACK, AgentMessage.feedback("reviewer", "fix").type());
        assertEquals(AgentMessage.Type.APPROVAL, AgentMessage.approval("reviewer", "ok").type());
        assertEquals(AgentMessage.Type.REJECTION, AgentMessage.rejection("reviewer", "no").type());
        assertEquals(AgentMessage.Type.ERROR, AgentMessage.error("worker", AgentRole.WORKER, "fail").type());
    }
}
