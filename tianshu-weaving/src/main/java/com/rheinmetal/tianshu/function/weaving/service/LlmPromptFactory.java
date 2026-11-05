package com.rheinmetal.tianshu.function.weaving.service;

import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.function.weaving.model.LlmModelInfo;
import com.rheinmetal.tianshu.function.weaving.model.LlmModelManager;
import com.rheinmetal.tianshu.libs.llm.ChatMessage;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Turns a protocol payload into a sendable {@link LlmPrompt}.
 *
 * <p>This is deliberately not a prompt assembler: messages are forwarded in order and unchanged.
 * The only work done here is validation (a request must carry at least one message) and filling the
 * sampling fields the caller left empty from the cloud model's catalog defaults.</p>
 */
public final class LlmPromptFactory {
    private final Supplier<String> samplingModelId;

    /**
     * @param samplingModelId provider model id whose catalog entry supplies sampling defaults; an
     *                        absent or unknown id simply means "use the protocol defaults"
     */
    public LlmPromptFactory(Supplier<String> samplingModelId) {
        this.samplingModelId = samplingModelId == null ? () -> "" : samplingModelId;
    }

    public LlmPrompt create(LLMPromptRequestPayload payload) {
        if (payload == null) {
            throw new LlmRequestRejectedException("LLM_EMPTY_REQUEST", "LLM_EMPTY_REQUEST");
        }
        List<ChatMessage> messages = payload.messages().stream()
                .filter(Objects::nonNull)
                .map(item -> new ChatMessage(item.role(), item.content()))
                .toList();
        if (messages.isEmpty()) {
            throw new LlmRequestRejectedException("LLM_EMPTY_MESSAGES", "LLM_EMPTY_MESSAGES");
        }
        return new LlmPrompt(
                messages,
                resolveSampling(payload.sampling()),
                payload.maxTokens(),
                payload.captureThinkingContent(),
                payload.reasoningTier()
        );
    }

    private LLMPromptRequestPayload.SamplingPayload resolveSampling(
            LLMPromptRequestPayload.SamplingPayload requested
    ) {
        LLMPromptRequestPayload.SamplingPayload effective =
                requested == null ? LLMPromptRequestPayload.SamplingPayload.empty() : requested;
        LlmModelInfo.SamplingSettings defaults = catalogDefaults();
        if (defaults == null) {
            return effective;
        }
        return new LLMPromptRequestPayload.SamplingPayload(
                effective.temperature() != null ? effective.temperature() : defaults.temperature,
                effective.topP() != null ? effective.topP() : defaults.topP,
                effective.frequencyPenalty() != null ? effective.frequencyPenalty() : defaults.frequencyPenalty,
                effective.presencePenalty() != null ? effective.presencePenalty() : defaults.presencePenalty
        );
    }

    private LlmModelInfo.SamplingSettings catalogDefaults() {
        String modelId = samplingModelId.get();
        LlmModelInfo info = LlmModelManager.getModelByName(modelId);
        return info == null ? null : info.getSamplingSettings();
    }
}
