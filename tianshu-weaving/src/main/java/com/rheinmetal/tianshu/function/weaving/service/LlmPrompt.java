package com.rheinmetal.tianshu.function.weaving.service;

import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.foundry.llm.ChatMessage;

import java.util.List;

/**
 * A request ready to be sent: messages already validated, sampling already resolved against the
 * model catalog, and the reasoning tier the caller selected ({@code null} follows the endpoint).
 */
public record LlmPrompt(
        List<ChatMessage> messages,
        LLMPromptRequestPayload.SamplingPayload sampling,
        int maxTokens,
        boolean captureThinkingContent,
        Integer reasoningTier
) {
    public LlmPrompt {
        messages = messages == null ? List.of() : List.copyOf(messages);
        sampling = sampling == null ? LLMPromptRequestPayload.SamplingPayload.empty() : sampling;
        maxTokens = Math.max(0, maxTokens);
        reasoningTier = reasoningTier != null && reasoningTier >= 0 ? reasoningTier : null;
    }

    public LlmPrompt(
            List<ChatMessage> messages,
            LLMPromptRequestPayload.SamplingPayload sampling,
            int maxTokens,
            boolean captureThinkingContent
    ) {
        this(messages, sampling, maxTokens, captureThinkingContent, null);
    }
}
