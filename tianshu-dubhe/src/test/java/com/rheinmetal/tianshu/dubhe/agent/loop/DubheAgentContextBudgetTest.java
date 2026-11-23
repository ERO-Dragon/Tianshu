package com.rheinmetal.tianshu.dubhe.agent.loop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DubheAgentContextBudgetTest {

    @Test
    void theBudgetReservesRoomForTheAnswerBeingGenerated() {
        DubheAgentContextBudget budget = DubheAgentContextBudget.forWindow(32_768);

        assertEquals(32_768 - 4_096, budget.budgetTokens());
        assertEquals(4_096, budget.toolResultTokens());
        assertEquals(2_048, budget.digestTokens());
    }

    @Test
    void compactionStartsWhenTheWindowIsNearlyFull() {
        assertEquals(20_480, DubheAgentContextBudget.forWindow(32_768).compactAtTokens());
        assertEquals(104_120, DubheAgentContextBudget.forWindow(131_072).compactAtTokens());
    }

    @Test
    void aSmallWindowStillCompactsAtHalfItsBudgetInsteadOfZero() {
        DubheAgentContextBudget budget = DubheAgentContextBudget.forWindow(8_192);

        assertEquals(2_048, budget.compactAtTokens());
    }

    @Test
    void aWindowThatCannotHoldTheOutputReserveIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> DubheAgentContextBudget.forWindow(4_096));
        assertThrows(IllegalArgumentException.class, () -> DubheAgentContextBudget.forWindow(1_000));
    }
}