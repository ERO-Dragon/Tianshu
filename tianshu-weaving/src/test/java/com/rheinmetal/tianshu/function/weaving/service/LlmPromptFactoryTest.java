package com.rheinmetal.tianshu.function.weaving.service;

import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptRequestPayload;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmPromptFactoryTest {

    @Test
    void anEmptyMessageListIsRejectedBeforeAnyRequestIsSent() {
        LlmPromptFactory factory = new LlmPromptFactory(() -> "deepseek-flash");

        LlmRequestRejectedException failure = assertThrows(
                LlmRequestRejectedException.class,
                () -> factory.create(new LLMPromptRequestPayload(
                        "req", List.of(), false, false, 0, LLMPromptRequestPayload.SamplingPayload.empty()))
        );

        assertEquals("LLM_EMPTY_MESSAGES", failure.getMessage());
        assertEquals("LLM_EMPTY_MESSAGES", failure.code());
    }

    @Test
    void aMissingPayloadIsRejected() {
        LlmPromptFactory factory = new LlmPromptFactory(() -> "");

        LlmRequestRejectedException failure = assertThrows(
                LlmRequestRejectedException.class, () -> factory.create(null)
        );
        assertEquals("LLM_EMPTY_REQUEST", failure.code());
    }

    @Test
    void messagesAreForwardedInOrderAndUnchanged() {
        LlmPromptFactory factory = new LlmPromptFactory(() -> "");
        LLMPromptRequestPayload payload = new LLMPromptRequestPayload(
                "req",
                List.of(
                        LLMPromptRequestPayload.MessageItemPayload.system("sys"),
                        LLMPromptRequestPayload.MessageItemPayload.user("hi"),
                        LLMPromptRequestPayload.MessageItemPayload.assistant("hello")
                ),
                true,
                true,
                256,
                LLMPromptRequestPayload.SamplingPayload.empty()
        );

        LlmPrompt prompt = factory.create(payload);

        assertEquals(List.of("sys", "hi", "hello"),
                prompt.messages().stream().map(message -> message.content).toList());
        assertEquals(List.of("system", "user", "assistant"),
                prompt.messages().stream().map(message -> message.role).toList());
        assertEquals(256, prompt.maxTokens());
        assertTrue(prompt.captureThinkingContent());
    }

    @Test
    void catalogDefaultsFillOnlyTheFieldsTheCallerLeftEmpty() {
        LlmPromptFactory factory = new LlmPromptFactory(() -> "deepseek-flash");
        LLMPromptRequestPayload payload = new LLMPromptRequestPayload(
                "req",
                List.of(LLMPromptRequestPayload.MessageItemPayload.user("hi")),
                false,
                false,
                0,
                new LLMPromptRequestPayload.SamplingPayload(0.7F, null, null, null)
        );

        LlmPrompt prompt = factory.create(payload);

        assertEquals(0.7F, prompt.sampling().temperature(), "the caller's value wins");
        assertEquals(0.95F, prompt.sampling().topP(), "the catalog default fills the gap");
        assertEquals(0.0F, prompt.sampling().frequencyPenalty());
        assertEquals(0.0F, prompt.sampling().presencePenalty());
    }

    @Test
    void anUnknownModelSimplyLeavesSamplingEmpty() {
        LlmPromptFactory factory = new LlmPromptFactory(() -> "no-such-model");
        LLMPromptRequestPayload payload = new LLMPromptRequestPayload(
                "req",
                List.of(LLMPromptRequestPayload.MessageItemPayload.user("hi")),
                false,
                false,
                0,
                LLMPromptRequestPayload.SamplingPayload.empty()
        );

        LlmPrompt prompt = factory.create(payload);

        assertNull(prompt.sampling().temperature());
        assertNull(prompt.sampling().topP());
    }
}
