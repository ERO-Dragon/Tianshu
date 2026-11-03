package com.rheinmetal.tianshu.function.weaving.contract;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LLMPromptRequestPayloadTest {

    @Test
    void normalizesRolesAndKeepsContentVerbatim() {
        LLMPromptRequestPayload payload = new LLMPromptRequestPayload(
                "  request-1 ",
                List.of(
                        new LLMPromptRequestPayload.MessageItemPayload("SYSTEM", " sys "),
                        new LLMPromptRequestPayload.MessageItemPayload("tool", "hello")
                ),
                true,
                true,
                128,
                LLMPromptRequestPayload.SamplingPayload.empty()
        );

        assertEquals("request-1", payload.requestId());
        assertEquals("system", payload.messages().get(0).role());
        assertEquals(" sys ", payload.messages().get(0).content(), "the transport must not trim prompt content");
        assertEquals("user", payload.messages().get(1).role(), "an unknown role degrades to user");
        assertTrue(payload.stream());
        assertTrue(payload.captureThinkingContent());
        assertEquals(128, payload.maxTokens());
    }

    @Test
    void defaultsAnEmptyRequestInsteadOfThrowing() {
        LLMPromptRequestPayload payload = new LLMPromptRequestPayload(
                null, null, null, null, -5, null
        );

        assertFalse(payload.stream());
        assertFalse(payload.captureThinkingContent());
        assertEquals(0, payload.maxTokens());
        assertTrue(payload.messages().isEmpty());
        assertTrue(payload.sampling().isEmpty());
        assertEquals("llm.request", payload.requestId());
    }

    @Test
    void dropsSamplingValuesOutsideTheProviderRange() {
        LLMPromptRequestPayload.SamplingPayload sampling = new LLMPromptRequestPayload.SamplingPayload(
                3.0F, -0.1F, 5.0F, Float.NaN
        );

        assertNull(sampling.temperature());
        assertNull(sampling.topP());
        assertNull(sampling.frequencyPenalty());
        assertNull(sampling.presencePenalty());
        assertTrue(sampling.isEmpty());
    }

    @Test
    void keepsSamplingValuesInsideTheProviderRange() {
        LLMPromptRequestPayload.SamplingPayload sampling = new LLMPromptRequestPayload.SamplingPayload(
                0.7F, 0.9F, -1.5F, 1.5F
        );

        assertEquals(0.7F, sampling.temperature());
        assertEquals(0.9F, sampling.topP());
        assertEquals(-1.5F, sampling.frequencyPenalty());
        assertEquals(1.5F, sampling.presencePenalty());
        assertFalse(sampling.isEmpty());
    }
}
