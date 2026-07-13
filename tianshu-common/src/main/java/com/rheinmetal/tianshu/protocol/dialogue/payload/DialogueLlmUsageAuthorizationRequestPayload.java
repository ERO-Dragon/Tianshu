package com.rheinmetal.tianshu.protocol.dialogue.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record DialogueLlmUsageAuthorizationRequestPayload(
        String sessionId,
        String requesterModuleId,
        String requesterParticipantId,
        String turnId,
        long timestampMillis
) implements ITianshuPayload {
    public DialogueLlmUsageAuthorizationRequestPayload {
        sessionId = requireText(sessionId, "sessionId");
        requesterModuleId = requireText(requesterModuleId, "requesterModuleId");
        requesterParticipantId = sanitize(requesterParticipantId);
        turnId = sanitize(turnId);
        timestampMillis = Math.max(0L, timestampMillis);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value.trim();
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}
