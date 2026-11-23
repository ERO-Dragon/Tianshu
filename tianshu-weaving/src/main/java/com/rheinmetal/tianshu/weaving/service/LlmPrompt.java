package com.rheinmetal.tianshu.weaving.service;

import com.rheinmetal.tianshu.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.weaving.contract.LLMToolSpecPayload;

import java.util.List;

/**
 * A request ready to be sent: messages already validated, sampling already resolved against the
 * model catalog, the reasoning tier the caller selected ({@code null} follows the endpoint), and the
 * tool catalogue the caller offered ({@code empty} = no tools).
 */
public record LlmPrompt(
        List<LlmChatMessage> messages,
        List<LLMToolSpecPayload> tools,
        String toolChoice,
        LLMPromptRequestPayload.SamplingPayload sampling,
        int maxTokens,
        boolean captureThinkingContent,
        Integer reasoningTier
) {
    public LlmPrompt {
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        toolChoice = toolChoice == null || toolChoice.isBlank() ? null : toolChoice.trim();
        sampling = sampling == null ? LLMPromptRequestPayload.SamplingPayload.empty() : sampling;
        maxTokens = Math.max(0, maxTokens);
        reasoningTier = reasoningTier != null && reasoningTier >= 0 ? reasoningTier : null;
    }

    /** A prompt without tools, in the field order this record had before tool transport existed. */
    public LlmPrompt(
            List<LlmChatMessage> messages,
            LLMPromptRequestPayload.SamplingPayload sampling,
            int maxTokens,
            boolean captureThinkingContent,
            Integer reasoningTier
    ) {
        this(messages, List.of(), null, sampling, maxTokens, captureThinkingContent, reasoningTier);
    }

    public LlmPrompt(
            List<LlmChatMessage> messages,
            LLMPromptRequestPayload.SamplingPayload sampling,
            int maxTokens,
            boolean captureThinkingContent
    ) {
        this(messages, List.of(), null, sampling, maxTokens, captureThinkingContent, null);
    }

    /** Whether the caller offered any tool for this request. */
    public boolean offersTools() {
        return !tools.isEmpty();
    }
}