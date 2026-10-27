package com.rheinmetal.tianshu.function.weaving.contract;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmStatusPayloadTest {
    @Test
    void unknownInferenceEventTypeDoesNotBecomeFailure() {
        LlmStatusPayload payload = new LlmStatusPayload(
                "task-1",
                "complex",
                "complex",
                "future_event",
                -1,
                null,
                -1,
                -1,
                null,
                0L
        );

        assertEquals(LlmStatusPayload.UNKNOWN, payload.eventType());
        assertEquals("COMPLEX", payload.taskType());
        assertEquals("COMPLEX", payload.lane());
        assertEquals(0, payload.priority());
        assertEquals(0, payload.replayCharacters());
        assertEquals(0, payload.generatedTokens());
    }
}
