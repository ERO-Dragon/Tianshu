package com.rheinmetal.tianshu.protocol.dialogue.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record DialogueArbitrationResultPayload(
        String requestId,
        String sessionId,
        boolean accepted,
        String ownerModuleId,
        String ownerParticipantId,
        String routeCapability,
        String reason,
        long processingDeadlineMillis
) implements ITianshuPayload {
    public DialogueArbitrationResultPayload {
        requestId = sanitize(requestId);
        sessionId = sanitize(sessionId);
        ownerModuleId = sanitize(ownerModuleId);
        ownerParticipantId = sanitize(ownerParticipantId);
        routeCapability = sanitize(routeCapability);
        reason = sanitize(reason);
        processingDeadlineMillis = Math.max(0L, processingDeadlineMillis);
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}
