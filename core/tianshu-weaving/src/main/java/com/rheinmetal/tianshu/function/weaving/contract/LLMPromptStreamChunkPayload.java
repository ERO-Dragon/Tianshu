package com.rheinmetal.tianshu.function.weaving.contract;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

/**
 * One incremental piece of a streaming answer, or the terminal frame that closes it.
 *
 * <p>Text and reasoning travel on separate channels; a terminal frame repeats the accumulated
 * reasoning text so a subscriber that joined late still sees it, and carries the provider's token
 * usage.</p>
 *
 * <p>The granularity is not guaranteed to be one provider token: a fast stream is coalesced into
 * short batches before publishing, so one chunk may carry several tokens. Callers must append
 * {@code text} in {@code index} order, never assume a chunk equals a token.</p>
 */
public record LLMPromptStreamChunkPayload(
        String requestId,
        String text,
        String thinkingContent,
        boolean finished,
        int index,
        String finishType,
        LLMPromptResultPayload.TokenUsagePayload usage,
        String errorMessage
) implements ITianshuPayload {

    public LLMPromptStreamChunkPayload {
        requestId = requestId == null || requestId.isBlank() ? "" : requestId.trim();
        text = text == null ? "" : text;
        thinkingContent = thinkingContent == null ? "" : thinkingContent;
        finishType = normalizeFinishType(finishType, finished);
        usage = usage == null ? LLMPromptResultPayload.TokenUsagePayload.empty() : usage;
        errorMessage = errorMessage == null || errorMessage.isBlank() ? null : errorMessage.trim();
    }

    private static String normalizeFinishType(String value, boolean finished) {
        if (!finished) {
            return "";
        }
        String normalized = value == null ? "" : value.trim().toUpperCase();
        return switch (normalized) {
            case "COMPLETED", "CANCELLED", "FAILED" -> normalized;
            default -> "COMPLETED";
        };
    }

    public static LLMPromptStreamChunkPayload chunk(String requestId, String text, int index) {
        return new LLMPromptStreamChunkPayload(
                requestId, text, "", false, index, "", LLMPromptResultPayload.TokenUsagePayload.empty(), null
        );
    }

    public static LLMPromptStreamChunkPayload thinking(String requestId, String thinkingContent, int index) {
        return new LLMPromptStreamChunkPayload(
                requestId, "", thinkingContent, false, index, "", LLMPromptResultPayload.TokenUsagePayload.empty(), null
        );
    }

    public static LLMPromptStreamChunkPayload end(String requestId, int index) {
        return end(requestId, index, "COMPLETED", LLMPromptResultPayload.TokenUsagePayload.empty(), null, "");
    }

    public static LLMPromptStreamChunkPayload end(
            String requestId,
            int index,
            String finishType,
            LLMPromptResultPayload.TokenUsagePayload usage,
            String errorMessage
    ) {
        return end(requestId, index, finishType, usage, errorMessage, "");
    }

    public static LLMPromptStreamChunkPayload end(
            String requestId,
            int index,
            String finishType,
            LLMPromptResultPayload.TokenUsagePayload usage,
            String errorMessage,
            String thinkingContent
    ) {
        return new LLMPromptStreamChunkPayload(
                requestId, "", thinkingContent, true, index, finishType, usage, errorMessage
        );
    }
}
