package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

import java.util.List;

public record LLMPromptStreamChunkPayload(
        String requestId,
        String text,
        boolean finished,
        int index,
        List<LLMPromptResultPayload.RagHitPayload> ragHits
) implements ITianshuPayload {

    public LLMPromptStreamChunkPayload {
        requestId = normalize(requestId);
        text = text == null ? "" : text.trim();
        ragHits = ragHits != null ? List.copyOf(ragHits) : List.of();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }

    public static LLMPromptStreamChunkPayload chunk(String requestId, String text, int index) {
        return new LLMPromptStreamChunkPayload(requestId, text, false, index, List.of());
    }

    public static LLMPromptStreamChunkPayload chunk(String requestId, String text, int index, List<LLMPromptResultPayload.RagHitPayload> ragHits) {
        return new LLMPromptStreamChunkPayload(requestId, text, false, index, ragHits);
    }

    public static LLMPromptStreamChunkPayload end(String requestId, int index) {
        return new LLMPromptStreamChunkPayload(requestId, "", true, index, List.of());
    }
}