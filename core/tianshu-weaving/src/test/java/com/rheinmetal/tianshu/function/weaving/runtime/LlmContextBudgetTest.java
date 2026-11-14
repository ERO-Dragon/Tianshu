package com.rheinmetal.tianshu.function.weaving.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmContextBudgetTest {

    @Test
    void budgetIsCapacityMinusOutputReserveAndHeadroom() {
        // 102400 -> prompt window 101888, headroom 6368 rounded up to 7168, budget 94720.
        assertEquals(94_720, LlmContextBudget.promptTokenBudget(102_400));
    }

    @Test
    void smallWindowsKeepTheMinimumHeadroom() {
        // 8192 -> headroom clamps to 4096, budget 8192 - 512 - 4096.
        assertEquals(3_584, LlmContextBudget.promptTokenBudget(8_192));
    }

    @Test
    void noConfiguredCapacityProducesNoBudget() {
        assertEquals(0, LlmContextBudget.promptTokenBudget(0));
        assertEquals(0, LlmContextBudget.promptTokenBudget(-1));
    }
}
