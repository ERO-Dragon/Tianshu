package com.rheinmetal.tianshu.protocol.payload;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmStatusPayloadTest {
    @Test
    void unknownInferenceEventTypeDoesNotBecomeFailure() {
        LlmStatusPayload payload = new LlmStatusPayload(
                "task-1",
                "task",
                "task",
                "future_event",
                -1,
                null,
                -1,
                -1,
                null,
                0L
        );

        assertEquals(LlmStatusPayload.UNKNOWN, payload.eventType());
        assertEquals("TASK", payload.taskType());
        assertEquals("TASK", payload.lane());
        assertEquals(0, payload.priority());
        assertEquals(0, payload.replayCharacters());
        assertEquals(0, payload.generatedTokens());
    }
}
