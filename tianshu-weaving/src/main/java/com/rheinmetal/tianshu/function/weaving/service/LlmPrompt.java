package com.rheinmetal.tianshu.function.weaving.service;

import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.libs.llm.ChatMessage;

import java.util.List;

/**
 * A request ready to be sent: messages already validated, sampling already resolved against the
 * model catalog, and the reasoning depth the caller asked for (empty means follow the endpoint).
 */
public record LlmPrompt(
        List<ChatMessage> messages,
        LLMPromptRequestPayload.SamplingPayload sampling,
        int maxTokens,
        boolean captureThinkingContent,
        String reasoningEffort
) {
    public LlmPrompt {
        messages = messages == null ? List.of() : List.copyOf(messages);
        sampling = sampling == null ? LLMPromptRequestPayload.SamplingPayload.empty() : sampling;
        maxTokens = Math.max(0, maxTokens);
        reasoningEffort = reasoningEffort == null ? "" : reasoningEffort.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public LlmPrompt(
            List<ChatMessage> messages,
            LLMPromptRequestPayload.SamplingPayload sampling,
            int maxTokens,
            boolean captureThinkingContent
    ) {
        this(messages, sampling, maxTokens, captureThinkingContent, "");
    }
}
