package com.rheinmetal.tianshu.function.weaving.service;

import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.libs.llm.ChatMessage;

import java.util.List;

/**
 * A request ready to be sent: messages already validated, sampling already resolved against the
 * model catalog. Nothing here builds or rewrites prompt content.
 */
public record LlmPrompt(
        List<ChatMessage> messages,
        LLMPromptRequestPayload.SamplingPayload sampling,
        int maxTokens,
        boolean captureThinkingContent
) {
    public LlmPrompt {
        messages = messages == null ? List.of() : List.copyOf(messages);
        sampling = sampling == null ? LLMPromptRequestPayload.SamplingPayload.empty() : sampling;
        maxTokens = Math.max(0, maxTokens);
    }
}
