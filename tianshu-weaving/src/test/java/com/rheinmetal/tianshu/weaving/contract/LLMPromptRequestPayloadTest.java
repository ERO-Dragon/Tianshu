package com.rheinmetal.tianshu.weaving.contract;

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
                        new LLMPromptRequestPayload.MessageItemPayload("developer", "hello")
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
        assertFalse(payload.offersTools(), "a request without a catalogue offers nothing");
    }

    /** {@code tool} is a real role now: a tool result is a message and must not be degraded. */
    @Test
    void aToolResultMessageKeepsItsRoleAndItsCallReference() {
        LLMPromptRequestPayload.MessageItemPayload result =
                LLMPromptRequestPayload.MessageItemPayload.toolResult("call-1", "done");

        assertEquals("tool", result.role());
        assertEquals("call-1", result.toolCallId());
        assertTrue(result.hasToolContent());

        LLMPromptRequestPayload.MessageItemPayload replay =
                LLMPromptRequestPayload.MessageItemPayload.assistantToolCalls("", List.of(
                        new LLMToolCallPayload("call-1", "block.place", "{\"x\":1}")));
        assertEquals("assistant", replay.role());
        assertEquals(1, replay.toolCalls().size());
    }

    @Test
    void offeringToolsIsCarriedAndEmptyCatalogueIsNormalized() {
        LLMPromptRequestPayload withTools = new LLMPromptRequestPayload(
                "r", List.of(LLMPromptRequestPayload.MessageItemPayload.user("hi")),
                List.of(LLMToolSpecPayload.noArguments("block.place", "place a block")),
                "auto", false, false, 0, null, LLMPromptRequestPayload.SamplingPayload.empty());

        assertTrue(withTools.offersTools());
        assertEquals("block.place", withTools.tools().get(0).name());
        assertEquals("auto", withTools.toolChoice());

        LLMPromptRequestPayload noTools = new LLMPromptRequestPayload(
                "r", List.of(LLMPromptRequestPayload.MessageItemPayload.user("hi")),
                null, "  ", false, false, 0, null, null);
        assertFalse(noTools.offersTools());
        assertNull(noTools.toolChoice(), "a blank choice means the endpoint default");
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
    void keepsNonNegativeReasoningTiersAndDropsNegatives() {
        assertEquals(0, payloadWithTier(0).reasoningTier());
        assertEquals(3, payloadWithTier(3).reasoningTier());
        assertNull(payloadWithTier(-1).reasoningTier(), "a negative tier is not a valid selection");
        assertNull(payloadWithTier(null).reasoningTier());
        assertTrue(payloadWithTier(3).hasReasoningOverride());
        assertFalse(payloadWithTier(null).hasReasoningOverride());
    }

    private static LLMPromptRequestPayload payloadWithTier(Integer tier) {
        return new LLMPromptRequestPayload(
                "request",
                List.of(LLMPromptRequestPayload.MessageItemPayload.user("hello")),
                false,
                false,
                0,
                tier,
                LLMPromptRequestPayload.SamplingPayload.empty()
        );
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
