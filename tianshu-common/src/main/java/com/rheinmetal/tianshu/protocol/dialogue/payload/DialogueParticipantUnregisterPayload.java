package com.rheinmetal.tianshu.protocol.dialogue.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record DialogueParticipantUnregisterPayload(String moduleId, String participantId, boolean allParticipants, long timestampMillis) implements ITianshuPayload {
    public DialogueParticipantUnregisterPayload {
        moduleId = requireText(moduleId, "moduleId");
        participantId = sanitize(participantId);
        timestampMillis = Math.max(0L, timestampMillis);
        if (!allParticipants && participantId.isBlank()) {
            throw new IllegalArgumentException("participantId cannot be blank when allParticipants is false");
        }
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
