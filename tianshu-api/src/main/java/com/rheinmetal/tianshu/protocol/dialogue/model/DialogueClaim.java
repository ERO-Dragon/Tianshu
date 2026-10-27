package com.rheinmetal.tianshu.protocol.dialogue.model;

public record DialogueClaim(
        String participantId,
        DialogueClaimSource source,
        int priority,
        String reason
) {
    public DialogueClaim {
        if (participantId == null || participantId.isBlank()) {
            throw new IllegalArgumentException("participantId cannot be blank");
        }
        participantId = participantId.trim();
        source = source == null ? DialogueClaimSource.UTTERANCE : source;
        reason = reason == null ? "" : reason.trim();
    }
}
