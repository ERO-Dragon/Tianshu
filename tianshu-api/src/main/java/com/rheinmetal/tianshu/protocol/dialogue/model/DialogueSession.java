package com.rheinmetal.tianshu.protocol.dialogue.model;

public record DialogueSession(
        String sessionId,
        String playerId,
        String ownerModuleId,
        String ownerParticipantId,
        DialogueSessionState state,
        String turnId,
        long createdAtMillis,
        long lastActiveAtMillis,
        long processingDeadlineMillis,
        DialogueReleaseReason releaseReason
) {
    public DialogueSession {
        sessionId = requireText(sessionId, "sessionId");
        playerId = requireText(playerId, "playerId");
        ownerModuleId = sanitize(ownerModuleId);
        ownerParticipantId = sanitize(ownerParticipantId);
        state = state == null ? DialogueSessionState.PENDING : state;
        turnId = sanitize(turnId);
        createdAtMillis = Math.max(0L, createdAtMillis);
        lastActiveAtMillis = Math.max(createdAtMillis, lastActiveAtMillis);
        processingDeadlineMillis = Math.max(lastActiveAtMillis, processingDeadlineMillis);
    }

    public boolean activeAt(long nowMillis) {
        return (state == DialogueSessionState.CLAIMED || state == DialogueSessionState.ACTIVE || state == DialogueSessionState.INTERRUPTING) && processingDeadlineMillis > nowMillis;
    }

    public boolean ownedBy(String moduleId, String participantId) {
        return ownerModuleId.equals(sanitize(moduleId)) && ownerParticipantId.equals(sanitize(participantId));
    }

    public DialogueSession claim(DialogueParticipantDescriptor owner, String turnId, long nowMillis) {
        return new DialogueSession(sessionId, playerId, owner.moduleId(), owner.participantId(), DialogueSessionState.CLAIMED, turnId, createdAtMillis, nowMillis, owner.turnProcessingPolicy().processingDeadlineAt(nowMillis), null);
    }

    public DialogueSession activate(long nowMillis) {
        return new DialogueSession(sessionId, playerId, ownerModuleId, ownerParticipantId, DialogueSessionState.ACTIVE, turnId, createdAtMillis, nowMillis, processingDeadlineMillis, null);
    }

    public DialogueSession extendProcessing(long nowMillis, long processingDeadlineMillis) {
        return new DialogueSession(sessionId, playerId, ownerModuleId, ownerParticipantId, DialogueSessionState.ACTIVE, turnId, createdAtMillis, nowMillis, processingDeadlineMillis, null);
    }

    public DialogueSession interrupting(long nowMillis) {
        return new DialogueSession(sessionId, playerId, ownerModuleId, ownerParticipantId, DialogueSessionState.INTERRUPTING, turnId, createdAtMillis, nowMillis, processingDeadlineMillis, null);
    }

    public DialogueSession terminal(DialogueSessionState state, DialogueReleaseReason reason, long nowMillis) {
        return new DialogueSession(sessionId, playerId, ownerModuleId, ownerParticipantId, state, turnId, createdAtMillis, nowMillis, processingDeadlineMillis, reason);
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
