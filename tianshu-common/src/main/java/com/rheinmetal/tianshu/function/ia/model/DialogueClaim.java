package com.rheinmetal.tianshu.function.ia.model;

public record DialogueClaim(
        String participantId,
        DialogueClaimStrength strength,
        DialogueAttentionDecay decay,
        int priority,
        String reason
) {
    public DialogueClaim {
        if (participantId == null || participantId.isBlank()) {
            throw new IllegalArgumentException("participantId cannot be blank");
        }
        participantId = participantId.trim();
        strength = strength == null ? DialogueClaimStrength.NORMAL : strength;
        decay = decay == null ? DialogueAttentionDecay.FAST : decay;
        reason = reason == null ? "" : reason.trim();
    }

    public double attention() {
        return strength.attention();
    }
}
