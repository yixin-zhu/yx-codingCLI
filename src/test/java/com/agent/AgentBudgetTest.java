package com.agent;

import com.agent.llm.LlmClient;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentBudgetTest {

    @Test
    void stagnationDetectedAfterRepeatedToolCalls() {
        AgentBudget budget = AgentBudget.defaults();
        List<LlmClient.ToolCall> sameCall = List.of(
                new LlmClient.ToolCall("call_1", "read_file", "{\"path\":\"a.txt\"}")
        );

        budget.recordToolCalls(sameCall);
        budget.recordToolCalls(sameCall);
        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());

        budget.recordToolCalls(sameCall);
        assertEquals(AgentBudget.ExitReason.STAGNATION_DETECTED, budget.check());
    }

    @Test
    void hardIterationLimitTriggers() {
        AgentBudget budget = new AgentBudget(3, 3);
        budget.beginIteration();
        budget.beginIteration();
        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());

        budget.beginIteration();
        assertEquals(AgentBudget.ExitReason.HARD_ITERATION_LIMIT, budget.check());
    }

    @Test
    void invalidConstructorArgumentsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AgentBudget(1, 50));
        assertThrows(IllegalArgumentException.class, () -> new AgentBudget(3, 0));
    }
}
