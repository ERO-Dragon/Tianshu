package com.rheinmetal.tianshu.protocol.payload;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LLMPromptRequestPayloadTest {

    @Test
    void taskPriorityIsClampedToPublicRange() {
        LLMPromptRequestPayload low = payload(-1);
        LLMPromptRequestPayload high = payload(1001);

        assertEquals(LLMPromptRequestPayload.MIN_TASK_PRIORITY, low.taskPriority());
        assertEquals(LLMPromptRequestPayload.MAX_TASK_PRIORITY, high.taskPriority());
    }

    private static LLMPromptRequestPayload payload(int priority) {
        return new LLMPromptRequestPayload(
                "request",
                0,
                0.7f,
                false,
                false,
                "TASK",
                priority,
                false,
                List.of(LLMPromptRequestPayload.ChunkPayload.message(
                        List.of(LLMPromptRequestPayload.MessageItemPayload.user("hello"))
                ))
        );
    }
}
