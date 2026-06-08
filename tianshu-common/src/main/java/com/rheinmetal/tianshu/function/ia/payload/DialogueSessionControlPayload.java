package com.rheinmetal.tianshu.function.ia.payload;

import com.rheinmetal.tianshu.function.ia.model.DialogueReleaseReason;
import com.rheinmetal.tianshu.function.ia.model.DialogueSessionControlAction;
import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record DialogueSessionControlPayload(
        String sessionId,
        String requesterModuleId,
        String requesterParticipantId,
        DialogueSessionControlAction action,
        DialogueReleaseReason reason,
        long requestedProcessingMillis,
        long timestampMillis
) implements ITianshuPayload {
    public DialogueSessionControlPayload {
        sessionId = requireText(sessionId, "sessionId");
        requesterModuleId = requireText(requesterModuleId, "requesterModuleId");
        requesterParticipantId = sanitize(requesterParticipantId);
        action = action == null ? DialogueSessionControlAction.RELEASE : action;
        requestedProcessingMillis = Math.max(0L, requestedProcessingMillis);
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
