package com.rheinmetal.tianshu.function.ia.model;

public record DialogueSession(
        String sessionId,
        String playerId,
        String ownerModuleId,
        String ownerParticipantId,
        DialogueSessionState state,
        String turnId,
        long createdAtMillis,
        long lastActiveAtMillis,
        long leaseExpireAtMillis,
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
        leaseExpireAtMillis = Math.max(lastActiveAtMillis, leaseExpireAtMillis);
    }

    public boolean activeAt(long nowMillis) {
        return (state == DialogueSessionState.CLAIMED || state == DialogueSessionState.ACTIVE || state == DialogueSessionState.INTERRUPTING) && leaseExpireAtMillis > nowMillis;
    }

    public boolean ownedBy(String moduleId, String participantId) {
        return ownerModuleId.equals(sanitize(moduleId)) && ownerParticipantId.equals(sanitize(participantId));
    }

    public DialogueSession claim(DialogueParticipantDescriptor owner, String turnId, long nowMillis) {
        return new DialogueSession(sessionId, playerId, owner.moduleId(), owner.participantId(), DialogueSessionState.CLAIMED, turnId, createdAtMillis, nowMillis, owner.leasePolicy().leaseExpireAt(nowMillis), null);
    }

    public DialogueSession activate(long nowMillis) {
        return new DialogueSession(sessionId, playerId, ownerModuleId, ownerParticipantId, DialogueSessionState.ACTIVE, turnId, createdAtMillis, nowMillis, leaseExpireAtMillis, null);
    }

    public DialogueSession renew(long nowMillis, long leaseExpireAtMillis) {
        return new DialogueSession(sessionId, playerId, ownerModuleId, ownerParticipantId, DialogueSessionState.ACTIVE, turnId, createdAtMillis, nowMillis, leaseExpireAtMillis, null);
    }

    public DialogueSession interrupting(long nowMillis) {
        return new DialogueSession(sessionId, playerId, ownerModuleId, ownerParticipantId, DialogueSessionState.INTERRUPTING, turnId, createdAtMillis, nowMillis, leaseExpireAtMillis, null);
    }

    public DialogueSession terminal(DialogueSessionState state, DialogueReleaseReason reason, long nowMillis) {
        return new DialogueSession(sessionId, playerId, ownerModuleId, ownerParticipantId, state, turnId, createdAtMillis, nowMillis, leaseExpireAtMillis, reason);
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
