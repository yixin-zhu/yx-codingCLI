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
    void tokenBudgetExceededWhenHardLimitSet() {
        AgentBudget budget = new AgentBudget(100, 3, 50);
        budget.recordTokens(60, 50);
        assertEquals(AgentBudget.ExitReason.TOKEN_BUDGET_EXCEEDED, budget.check());
    }

    @Test
    void cachedTokensAccumulated() {
        AgentBudget budget = AgentBudget.defaults();
        budget.recordTokens(100, 20, 40);
        assertEquals(40, budget.totalCachedInputTokens());
    }

    @Test
    void fromLlmClientUsesUnlimitedTokenBudgetByDefault() {
        AgentBudget budget = AgentBudget.fromLlmClient(null);
        budget.recordTokens(1_000_000, 1_000_000);
        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());
    }

    @Test
    void invalidConstructorArgumentsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AgentBudget(1, 50));
        assertThrows(IllegalArgumentException.class, () -> new AgentBudget(3, 0));
    }
}
