package com.rheinmetal.tianshu.protocol.dialogue.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record DialogueScoreResponsePayload(
        String requestId,
        String participantId,
        double score,
        long responseTimestampMillis,
        boolean terminalResult
) implements ITianshuPayload {
    public DialogueScoreResponsePayload {
        requestId = requireText(requestId, "requestId");
        participantId = requireText(participantId, "participantId");
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException("score must be finite");
        }
        score = Math.max(0.0D, Math.min(1.0D, score));
        responseTimestampMillis = Math.max(0L, responseTimestampMillis);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value.trim();
    }
}
