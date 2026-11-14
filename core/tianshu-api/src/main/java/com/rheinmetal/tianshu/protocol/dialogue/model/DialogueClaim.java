package com.rheinmetal.tianshu.protocol.dialogue.model;

/**
 * One participant's hard claim on the current turn.
 *
 * <p>The claim names the participant by both of its coordinates. A participant id is only stable inside its
 * own module — two modules may both call their participant {@code "default"} — so the pair, not the id
 * alone, is what identifies the claimant a decision is about.</p>
 */
public record DialogueClaim(
        String moduleId,
        String participantId,
        DialogueClaimSource source,
        int priority,
        String reason
) {
    public DialogueClaim {
        moduleId = require(moduleId, "moduleId");
        participantId = require(participantId, "participantId");
        source = source == null ? DialogueClaimSource.UTTERANCE : source;
        reason = reason == null ? "" : reason.trim();
    }

    /** The module-qualified identity of the claimant, as the registry keys it. */
    public String qualifiedKey() {
        return moduleId + ":" + participantId;
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value.trim();
    }
}