package com.rheinmetal.tianshu.protocol.dialogue.payload;

import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueReleaseReason;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueSessionEventType;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueSessionState;
import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record DialogueSessionEventPayload(
        String sessionId,
        String playerId,
        String ownerModuleId,
        String ownerParticipantId,
        DialogueSessionState state,
        DialogueSessionEventType eventType,
        DialogueReleaseReason releaseReason,
        String reasonCode,
        long timestampMillis
) implements ITianshuPayload {
    public DialogueSessionEventPayload {
        sessionId = sanitize(sessionId);
        playerId = sanitize(playerId);
        ownerModuleId = sanitize(ownerModuleId);
        ownerParticipantId = sanitize(ownerParticipantId);
        state = state == null ? DialogueSessionState.PENDING : state;
        eventType = eventType == null ? DialogueSessionEventType.CONVERSATION_REJECTED : eventType;
        reasonCode = sanitize(reasonCode);
        timestampMillis = Math.max(0L, timestampMillis);
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}
