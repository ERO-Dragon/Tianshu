package com.rheinmetal.tianshu.function.ia.contract;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

/**
 * The arbiter's broadcast of one user interrupt request, addressed to the participant it resolved.
 *
 * <p>It is a request, not a state change: the named participant decides what to stop and whether to release
 * its own work. The session stays exactly as it was, which is why this payload carries no state and no
 * release reason.</p>
 */
public record DialogueInterruptRequestedPayload(
        String playerId,
        String targetModuleId,
        String targetParticipantId,
        long occurredAtMillis
) implements ITianshuPayload {
    public DialogueInterruptRequestedPayload {
        playerId = requireText(playerId, "playerId");
        targetModuleId = requireText(targetModuleId, "targetModuleId");
        targetParticipantId = requireText(targetParticipantId, "targetParticipantId");
        occurredAtMillis = occurredAtMillis > 0L ? occurredAtMillis : System.currentTimeMillis();
    }

    /** Whether this request names the given participant; the only check a receiver needs before acting. */
    public boolean addresses(String moduleId, String participantId) {
        return targetModuleId.equals(moduleId == null ? "" : moduleId.trim())
                && targetParticipantId.equals(participantId == null ? "" : participantId.trim());
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value.trim();
    }
}