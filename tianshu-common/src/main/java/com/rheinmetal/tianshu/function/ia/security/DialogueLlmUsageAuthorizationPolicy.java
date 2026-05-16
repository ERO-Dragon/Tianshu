package com.rheinmetal.tianshu.function.ia.security;

import com.rheinmetal.tianshu.function.ia.model.DialogueSession;

public final class DialogueLlmUsageAuthorizationPolicy {
    private final DialogueAccessPolicy accessPolicy;

    public DialogueLlmUsageAuthorizationPolicy(DialogueAccessPolicy accessPolicy) {
        this.accessPolicy = accessPolicy;
    }

    public DialogueAccessDecision authorize(DialogueSession session, String requesterModuleId, String requesterParticipantId, String turnId, long nowMillis) {
        if (session == null) {
            return DialogueAccessDecision.deny("SESSION_MISSING", "Dialogue session is missing");
        }
        if (!session.activeAt(nowMillis)) {
            return DialogueAccessDecision.deny("SESSION_NOT_ACTIVE", "Dialogue session is not active");
        }
        if (!session.turnId().isBlank() && turnId != null && !turnId.isBlank() && !session.turnId().equals(turnId.trim())) {
            return DialogueAccessDecision.deny("TURN_MISMATCH", "Dialogue turn does not match session");
        }
        return accessPolicy.authorizeDialogueBodyDelivery(session, requesterModuleId, requesterParticipantId);
    }
}
