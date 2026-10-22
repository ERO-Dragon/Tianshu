package com.rheinmetal.tianshu.protocol.dialogue.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record DialogueOwnerPreviewPayload(
        String playerId,
        String moduleId,
        String participantId,
        String displayName,
        long updatedAtMillis
) implements ITianshuPayload {
    public DialogueOwnerPreviewPayload {
        playerId = sanitize(playerId);
        moduleId = sanitize(moduleId);
        participantId = sanitize(participantId);
        displayName = sanitize(displayName);
        updatedAtMillis = Math.max(0L, updatedAtMillis);
    }

    public boolean sameOwner(DialogueOwnerPreviewPayload other) {
        return other != null
                && playerId.equals(other.playerId())
                && moduleId.equals(other.moduleId())
                && participantId.equals(other.participantId())
                && displayName.equals(other.displayName());
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}
