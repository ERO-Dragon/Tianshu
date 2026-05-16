package com.rheinmetal.tianshu.function.ia.model;

public record DialogueArbitrationDecision(
        boolean accepted,
        DialogueParticipantDescriptor owner,
        DialogueClaim claim,
        String reason,
        boolean ownerChanged
) {
    public DialogueArbitrationDecision {
        if (accepted && owner == null) {
            throw new IllegalArgumentException("accepted decision requires owner");
        }
        reason = reason == null ? "" : reason.trim();
    }

    public static DialogueArbitrationDecision accepted(DialogueParticipantDescriptor owner, DialogueClaim claim, String reason, boolean ownerChanged) {
        return new DialogueArbitrationDecision(true, owner, claim, reason, ownerChanged);
    }

    public static DialogueArbitrationDecision rejected(String reason) {
        return new DialogueArbitrationDecision(false, null, null, reason, false);
    }
}
