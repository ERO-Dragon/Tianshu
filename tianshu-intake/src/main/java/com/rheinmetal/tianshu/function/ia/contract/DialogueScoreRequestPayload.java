package com.rheinmetal.tianshu.function.ia.contract;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record DialogueScoreRequestPayload(
        String requestId,
        String participantId,
        DialogueScoringContext context
) implements ITianshuPayload {
    public DialogueScoreRequestPayload {
        requestId = requireText(requestId, "requestId");
        participantId = requireText(participantId, "participantId");
        if (context == null) {
            throw new IllegalArgumentException("context cannot be null");
        }
        if (!requestId.equals(context.requestId())) {
            throw new IllegalArgumentException("requestId must match scoring context");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value.trim();
    }
}
