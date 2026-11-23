package com.rheinmetal.tianshu.weaving.contract;

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
 * text when it sends one; the visible answer never mixes it in. {@code reasoningTier} is the index
 * into the selected model's reasoning ladder ({@code 0} = the model's least reasoning, usually the
 * non-thinking slot); {@code null} follows the endpoint default.</p>
 */
public record LLMPromptRequestPayload(
        String requestId,
        List<MessageItemPayload> messages,
        List<LLMToolSpecPayload> tools,
        String toolChoice,
        Boolean stream,
        Boolean captureThinkingContent,
        Integer maxTokens,
        Integer reasoningTier,
        SamplingPayload sampling
) implements ITianshuPayload {

    public static final LLMPromptRequestPayload EMPTY = new LLMPromptRequestPayload(
            "llm.request", List.of(), List.of(), null, false, false, 0, null, SamplingPayload.empty()
    );

    public LLMPromptRequestPayload {
        requestId = normalizeRequestId(requestId);
        messages = messages == null ? List.of() : List.copyOf(messages);
        // The catalogue is the caller's: an empty list means "no tools offered", which is also what
        // every caller that predates tool transport sends.
        tools = tools == null ? List.of() : List.copyOf(tools);
        toolChoice = toolChoice == null || toolChoice.isBlank() ? null : toolChoice.trim();
        stream = stream != null && stream;
        captureThinkingContent = captureThinkingContent != null && captureThinkingContent;
        maxTokens = maxTokens != null && maxTokens > 0 ? maxTokens : 0;
        reasoningTier = reasoningTier != null && reasoningTier >= 0 ? reasoningTier : null;
        sampling = sampling == null ? SamplingPayload.empty() : sampling;
    }

    /**
     * A request without tools, in the field order this payload had before tool transport existed.
     * Kept so callers that only chat do not have to name two fields they do not use.
     */
    public LLMPromptRequestPayload(
            String requestId,
            List<MessageItemPayload> messages,
            Boolean stream,
            Boolean captureThinkingContent,
            Integer maxTokens,
            Integer reasoningTier,
            SamplingPayload sampling
    ) {
        this(requestId, messages, List.of(), null, stream, captureThinkingContent, maxTokens, reasoningTier, sampling);
    }

    /** Same request without a reasoning override: follow whatever the endpoint does by default. */
    public LLMPromptRequestPayload(
            String requestId,
            List<MessageItemPayload> messages,
            Boolean stream,
            Boolean captureThinkingContent,
            Integer maxTokens,
            SamplingPayload sampling
    ) {
        this(requestId, messages, List.of(), null, stream, captureThinkingContent, maxTokens, null, sampling);
    }

    public boolean hasReasoningOverride() {
        return reasoningTier != null;
    }

    /** Whether the caller offered any tool for this request. */
    public boolean offersTools() {
        return !tools.isEmpty();
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

    /**
     * One message of the conversation.
     *
     * <p>Plain text needs only {@code role} and {@code content}. The two tool fields exist because a
     * tool-using conversation cannot be expressed without them: the assistant turn that asked for a
     * call must be replayed with that call (providers require it before a result may follow), and a
     * result message is a {@code tool} message that names the call it answers. Weaving forwards all of
     * it; it never decides that a call exists.</p>
     *
     * @param toolCallId id of the call a {@code tool} message answers; empty on other roles
     * @param toolCalls  calls an {@code assistant} message is replaying; empty on other roles
     */
    public record MessageItemPayload(
            String role,
            String content,
            String toolCallId,
            List<LLMToolCallPayload> toolCalls
    ) implements ITianshuPayload {
        public MessageItemPayload {
            role = normalizeRole(role);
            content = content == null ? "" : content;
            toolCallId = toolCallId == null ? "" : toolCallId.trim();
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }

        /** A message with no tool involvement, which is what most of a conversation is. */
        public MessageItemPayload(String role, String content) {
            this(role, content, "", List.of());
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

        /** The assistant turn that asked for calls; it must precede their results. */
        public static MessageItemPayload assistantToolCalls(String content, List<LLMToolCallPayload> calls) {
            return new MessageItemPayload("assistant", content, "", calls);
        }

        /** The result of one call, as a {@code tool} message. */
        public static MessageItemPayload toolResult(String toolCallId, String content) {
            return new MessageItemPayload("tool", content, toolCallId, List.of());
        }

        /** Whether this message carries anything only a tool-using conversation has. */
        public boolean hasToolContent() {
            return !toolCallId.isEmpty() || !toolCalls.isEmpty();
        }
    }

    private static String normalizeRequestId(String value) {
        return value == null || value.isBlank() ? "llm.request" : value.trim();
    }

    /**
     * The roles an OpenAI-compatible endpoint accepts. {@code tool} is a result message and is the
     * only role besides {@code assistant} that carries a tool reference. Anything else degrades to
     * {@code user} instead of failing the request, which keeps a malformed caller from taking the lane
     * down.
     */
    private static String normalizeRole(String role) {
        if (role == null) {
            return "user";
        }
        return switch (role.trim().toLowerCase()) {
            case "system", "assistant", "user", "tool" -> role.trim().toLowerCase();
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
