package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

import java.util.List;

public record LLMPromptStreamChunkPayload(
        String requestId,
        String text,
        boolean finished,
        int index,
        List<LLMPromptResultPayload.RagHitPayload> ragHits,
        String finishType,
        LLMPromptResultPayload.TokenUsagePayload usage,
        String errorMessage
) implements ITianshuPayload {

    public LLMPromptStreamChunkPayload {
        requestId = normalize(requestId);
        text = text == null ? "" : text;
        ragHits = ragHits != null ? List.copyOf(ragHits) : List.of();
        finishType = normalizeFinishType(finishType, finished);
        usage = usage == null ? LLMPromptResultPayload.TokenUsagePayload.empty() : usage;
        errorMessage = errorMessage == null || errorMessage.isBlank() ? null : errorMessage.trim();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
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
        return new LLMPromptStreamChunkPayload(requestId, text, false, index, List.of(), "", LLMPromptResultPayload.TokenUsagePayload.empty(), null);
    }

    public static LLMPromptStreamChunkPayload chunk(String requestId, String text, int index, List<LLMPromptResultPayload.RagHitPayload> ragHits) {
        return new LLMPromptStreamChunkPayload(requestId, text, false, index, ragHits, "", LLMPromptResultPayload.TokenUsagePayload.empty(), null);
    }

    public static LLMPromptStreamChunkPayload end(String requestId, int index) {
        return end(requestId, index, "COMPLETED", LLMPromptResultPayload.TokenUsagePayload.empty(), null);
    }

    public static LLMPromptStreamChunkPayload end(String requestId, int index, String finishType, LLMPromptResultPayload.TokenUsagePayload usage, String errorMessage) {
        return new LLMPromptStreamChunkPayload(requestId, "", true, index, List.of(), finishType, usage, errorMessage);
    }
}
