package com.rheinmetal.tianshu.function.ia.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record DialogueLlmUsageAuthorizationResultPayload(
        String sessionId,
        boolean allowed,
        String requesterModuleId,
        String requesterParticipantId,
        String ownerModuleId,
        String ownerParticipantId,
        String reasonCode,
        String message,
        long leaseExpireAtMillis
) implements ITianshuPayload {
    public DialogueLlmUsageAuthorizationResultPayload {
        sessionId = sanitize(sessionId);
        requesterModuleId = sanitize(requesterModuleId);
        requesterParticipantId = sanitize(requesterParticipantId);
        ownerModuleId = sanitize(ownerModuleId);
        ownerParticipantId = sanitize(ownerParticipantId);
        reasonCode = sanitize(reasonCode);
        message = sanitize(message);
        leaseExpireAtMillis = Math.max(0L, leaseExpireAtMillis);
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}
