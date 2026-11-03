package com.rheinmetal.tianshu.function.weaving.contract;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

import java.util.List;

/**
 * One inference request: the conversation to send, and the sampling that shapes the answer.
 *
 * <p>The module is a transport, not an assistant: prompt assembly, context budgeting and tool
 * orchestration belong to the caller. A request therefore carries a finished message list — no RAG
 * chunks, no dialogue identity, no execution lane — and the module sends exactly those messages.</p>
 *
 * <p>{@code stream} only decides whether incremental chunks are published; both values use the same
 * single HTTP stream internally. {@code captureThinkingContent} asks for the provider's reasoning
 * text when it sends one; the visible answer never mixes it in.</p>
 */
public record LLMPromptRequestPayload(
        String requestId,
        List<MessageItemPayload> messages,
        Boolean stream,
        Boolean captureThinkingContent,
        Integer maxTokens,
        SamplingPayload sampling
) implements ITianshuPayload {

    public static final LLMPromptRequestPayload EMPTY = new LLMPromptRequestPayload(
            "llm.request", List.of(), false, false, 0, SamplingPayload.empty()
    );

    public LLMPromptRequestPayload {
        requestId = normalizeRequestId(requestId);
        messages = messages == null ? List.of() : List.copyOf(messages);
        stream = stream != null && stream;
        captureThinkingContent = captureThinkingContent != null && captureThinkingContent;
        maxTokens = maxTokens != null && maxTokens > 0 ? maxTokens : 0;
        sampling = sampling == null ? SamplingPayload.empty() : sampling;
    }

    /**
     * The sampling overrides a request may set, limited to the four fields an OpenAI-compatible
     * completion actually accepts. A {@code null} field means "follow the model catalog default";
     * out-of-range values are dropped for the same reason.
     */
    public record SamplingPayload(
            Float temperature,
            Float topP,
            Float frequencyPenalty,
            Float presencePenalty
    ) implements ITianshuPayload {
        public SamplingPayload {
            temperature = rangeOrNull(temperature, 0.0F, 2.0F);
            topP = rangeOrNull(topP, 0.0F, 1.0F);
            frequencyPenalty = rangeOrNull(frequencyPenalty, -2.0F, 2.0F);
            presencePenalty = rangeOrNull(presencePenalty, -2.0F, 2.0F);
        }

        public static SamplingPayload empty() {
            return new SamplingPayload(null, null, null, null);
        }

        public boolean isEmpty() {
            return temperature == null && topP == null && frequencyPenalty == null && presencePenalty == null;
        }
    }

    public record MessageItemPayload(
            String role,
            String content
    ) implements ITianshuPayload {
        public MessageItemPayload {
            role = normalizeRole(role);
            content = content == null ? "" : content;
        }

        public static MessageItemPayload of(String role, String content) {
            return new MessageItemPayload(role, content);
        }

        public static MessageItemPayload system(String content) {
            return new MessageItemPayload("system", content);
        }

        public static MessageItemPayload user(String content) {
            return new MessageItemPayload("user", content);
        }

        public static MessageItemPayload assistant(String content) {
            return new MessageItemPayload("assistant", content);
        }
    }

    private static String normalizeRequestId(String value) {
        return value == null || value.isBlank() ? "llm.request" : value.trim();
    }

    /**
     * The three roles an OpenAI-compatible endpoint accepts. Anything else degrades to {@code user}
     * instead of failing the request, which keeps a malformed caller from taking the lane down.
     */
    private static String normalizeRole(String role) {
        if (role == null) {
            return "user";
        }
        return switch (role.trim().toLowerCase()) {
            case "system", "assistant", "user" -> role.trim().toLowerCase();
            default -> "user";
        };
    }

    private static Float rangeOrNull(Float value, float min, float max) {
        if (value == null || value.isNaN() || value < min || value > max) {
            return null;
        }
        return value;
    }
}
