package com.rheinmetal.tianshu.protocol.dialogue.model;

public record DialogueAttentionState(
        String playerId,
        String ownerModuleId,
        String ownerParticipantId,
        double initialAttention,
        DialogueAttentionDecay decay,
        long updatedAtMillis
) {
    public static final double DEFAULT_OWNER_BASELINE = 0.4D;

    public DialogueAttentionState {
        playerId = requireText(playerId, "playerId");
        ownerModuleId = requireText(ownerModuleId, "ownerModuleId");
        ownerParticipantId = requireText(ownerParticipantId, "ownerParticipantId");
        initialAttention = clamp(initialAttention);
        decay = decay == null ? DialogueAttentionDecay.FAST : decay;
        updatedAtMillis = Math.max(0L, updatedAtMillis);
    }

    public double attentionAt(long nowMillis) {
        long elapsedMillis = Math.max(0L, nowMillis - updatedAtMillis);
        double elapsedSeconds = elapsedMillis / 1_000.0D;
        return clamp(initialAttention - decay.perSecond() * elapsedSeconds);
    }

    public boolean beatsDefaultOwnerAt(long nowMillis) {
        return attentionAt(nowMillis) > DEFAULT_OWNER_BASELINE;
    }

    public boolean ownedBy(String moduleId, String participantId) {
        return ownerModuleId.equals(sanitize(moduleId)) && ownerParticipantId.equals(sanitize(participantId));
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private static String requireText(String value, String name) {
        String normalized = sanitize(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return normalized;
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}
