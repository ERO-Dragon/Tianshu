package com.rheinmetal.tianshu.function.ia.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record DialogueArbitrationResultPayload(
        String requestId,
        String sessionId,
        boolean accepted,
        String ownerModuleId,
        String ownerParticipantId,
        String routeCapability,
        String routeTopic,
        String reason,
        long leaseExpireAtMillis
) implements ITianshuPayload {
    public DialogueArbitrationResultPayload {
        requestId = sanitize(requestId);
        sessionId = sanitize(sessionId);
        ownerModuleId = sanitize(ownerModuleId);
        ownerParticipantId = sanitize(ownerParticipantId);
        routeCapability = sanitize(routeCapability);
        routeTopic = sanitize(routeTopic);
        reason = sanitize(reason);
        leaseExpireAtMillis = Math.max(0L, leaseExpireAtMillis);
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}
