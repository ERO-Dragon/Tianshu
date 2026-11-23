package com.rheinmetal.tianshu.weaving.contract;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

/**
 * Terminal result of one inference request: what the provider answered, why it failed, and what it
 * billed. {@code text} is always the visible answer; reasoning text travels separately.
 *
 * <p>{@code truncated} states that a <em>completed</em> answer stopped at the provider's completion
 * cap rather than at its end. The text is still usable, which is why the status stays
 * {@code COMPLETED}; what the flag removes is the ambiguity — a caller that needs a whole answer has
 * to be able to tell. A cancelled or failed call is never truncated: its text is a prefix of an
 * interrupted call, and the status already says so.</p>
 */
public record LLMPromptResultPayload(
        String requestId,
        String status,
        String text,
        String thinkingContent,
        String errorCode,
        String errorMessage,
        TokenUsagePayload usage,
        boolean truncated
) implements ITianshuPayload {

    public LLMPromptResultPayload {
        requestId = normalize(requestId);
        status = normalizeStatus(status);
        text = text == null ? "" : text;
        thinkingContent = thinkingContent == null ? "" : thinkingContent;
        errorCode = blankToNull(errorCode);
        errorMessage = blankToNull(errorMessage);
        usage = usage == null ? TokenUsagePayload.empty() : usage;
        // Truncation only means something for an answer the provider completed; a cancelled or failed
        // call carries a prefix, which its status already states.
        truncated = truncated && "COMPLETED".equals(status);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizeStatus(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        return switch (normalized) {
            case "COMPLETED", "CANCELLED", "FAILED" -> normalized;
            default -> "FAILED";
        };
    }

    public static LLMPromptResultPayload completed(String requestId, String text) {
        return completed(requestId, text, "", TokenUsagePayload.empty(), false);
    }

    public static LLMPromptResultPayload completed(
            String requestId,
            String text,
            String thinkingContent,
            TokenUsagePayload usage
    ) {
        return completed(requestId, text, thinkingContent, usage, false);
    }

    /** A completed answer, marked when the provider stopped it at its completion cap. */
    public static LLMPromptResultPayload completed(
            String requestId,
            String text,
            String thinkingContent,
            TokenUsagePayload usage,
            boolean truncated
    ) {
        return new LLMPromptResultPayload(requestId, "COMPLETED", text, thinkingContent, null, null, usage, truncated);
    }

    public static LLMPromptResultPayload cancelled(String requestId, String text) {
        return cancelled(requestId, text, "", TokenUsagePayload.empty());
    }

    public static LLMPromptResultPayload cancelled(
            String requestId,
            String text,
            String thinkingContent,
            TokenUsagePayload usage
    ) {
        return new LLMPromptResultPayload(requestId, "CANCELLED", text, thinkingContent, null, null, usage, false);
    }

    public static LLMPromptResultPayload failed(String requestId, String errorCode, String errorMessage) {
        return failed(requestId, errorCode, errorMessage, "", "", TokenUsagePayload.empty());
    }

    public static LLMPromptResultPayload failed(
            String requestId,
            String errorCode,
            String errorMessage,
            String partialText,
            String thinkingContent,
            TokenUsagePayload usage
    ) {
        return new LLMPromptResultPayload(
                requestId,
                "FAILED",
                partialText,
                thinkingContent,
                errorCode,
                errorMessage,
                usage,
                false
        );
    }

    public boolean isCompleted() {
        return "COMPLETED".equals(status);
    }

    public boolean isCancelled() {
        return "CANCELLED".equals(status);
    }

    public boolean isFailed() {
        return "FAILED".equals(status);
    }

    public record TokenUsagePayload(
            int promptTokens,
            int completionTokens,
            int thinkingTokens,
            int outputTokens,
            int totalTokens
    ) implements ITianshuPayload {
        public TokenUsagePayload(int promptTokens, int completionTokens, int totalTokens) {
            this(promptTokens, completionTokens, 0, completionTokens, totalTokens);
        }

        public TokenUsagePayload {
            promptTokens = Math.max(0, promptTokens);
            completionTokens = Math.max(0, completionTokens);
            thinkingTokens = Math.max(0, thinkingTokens);
            int computedOutput = completionTokens + thinkingTokens;
            outputTokens = outputTokens > 0 ? outputTokens : computedOutput;
            int computedTotal = promptTokens + outputTokens;
            totalTokens = totalTokens > 0 ? totalTokens : computedTotal;
        }

        public static TokenUsagePayload empty() {
            return new TokenUsagePayload(0, 0, 0, 0, 0);
        }
    }
}
