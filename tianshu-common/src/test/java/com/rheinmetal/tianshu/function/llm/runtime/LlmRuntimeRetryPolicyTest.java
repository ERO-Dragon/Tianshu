package com.rheinmetal.tianshu.function.llm.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmRuntimeRetryPolicyTest {

    @Test
    void backsOffFromFiveSecondsToOneMinute() {
        assertEquals(5_000L, LlmRuntimeRetryPolicy.delayMillisForAttempt(1));
        assertEquals(10_000L, LlmRuntimeRetryPolicy.delayMillisForAttempt(2));
        assertEquals(20_000L, LlmRuntimeRetryPolicy.delayMillisForAttempt(3));
        assertEquals(40_000L, LlmRuntimeRetryPolicy.delayMillisForAttempt(4));
        assertEquals(60_000L, LlmRuntimeRetryPolicy.delayMillisForAttempt(5));
    }

    @Test
    void theDelayNeverGrowsPastTheCeiling() {
        assertEquals(LlmRuntimeRetryPolicy.MAX_DELAY_MILLIS, LlmRuntimeRetryPolicy.delayMillisForAttempt(12));
        assertEquals(LlmRuntimeRetryPolicy.BASE_DELAY_MILLIS, LlmRuntimeRetryPolicy.delayMillisForAttempt(0));
        assertEquals(LlmRuntimeRetryPolicy.BASE_DELAY_MILLIS, LlmRuntimeRetryPolicy.delayMillisForAttempt(-3));
    }

    @Test
    void allowsExactlyTheAdvertisedNumberOfAttempts() {
        for (int completed = 0; completed < LlmRuntimeRetryPolicy.MAX_ATTEMPTS; completed++) {
            assertTrue(LlmRuntimeRetryPolicy.canRetry(completed), "attempt " + (completed + 1) + " must be allowed");
        }
        assertFalse(LlmRuntimeRetryPolicy.canRetry(LlmRuntimeRetryPolicy.MAX_ATTEMPTS));
        assertFalse(LlmRuntimeRetryPolicy.canRetry(LlmRuntimeRetryPolicy.MAX_ATTEMPTS + 4));
    }
}
