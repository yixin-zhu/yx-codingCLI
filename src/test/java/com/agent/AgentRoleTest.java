package com.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AgentRoleTest {

    @Test
    void shouldExposeDisplayNameAndDescription() {
        assertEquals("规划者", AgentRole.PLANNER.getDisplayName());
        assertEquals("执行者", AgentRole.WORKER.getDisplayName());
        assertEquals("检查者", AgentRole.REVIEWER.getDisplayName());
        assertNotNull(AgentRole.PLANNER.getDescription());
    }
}
