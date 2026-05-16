package com.rheinmetal.tianshu.function.ia.security;

import com.rheinmetal.tianshu.function.ia.model.DialogueSession;
import com.rheinmetal.tianshu.function.ia.payload.DialogueSessionEventPayload;

public final class DialogueAccessController implements DialogueAccessPolicy {
    public DialogueAccessDecision authorizeDialogueBodyDelivery(DialogueSession session, String moduleId, String participantId) {
        if (session == null) {
            return DialogueAccessDecision.deny("SESSION_MISSING", "Dialogue session is missing");
        }
        if (!session.ownedBy(moduleId, participantId)) {
            return DialogueAccessDecision.deny("NOT_SESSION_OWNER", "Target participant is not dialogue session owner");
        }
        return DialogueAccessDecision.allow();
    }

    public DialogueAccessDecision authorizeSessionControl(DialogueSession session, String moduleId, String participantId) {
        if (session == null) {
            return DialogueAccessDecision.deny("SESSION_MISSING", "Dialogue session is missing");
        }
        if (!session.ownedBy(moduleId, participantId)) {
            return DialogueAccessDecision.deny("NOT_SESSION_OWNER", "Requester is not dialogue session owner");
        }
        return DialogueAccessDecision.allow();
    }

    public DialogueAccessDecision authorizePublicEvent(DialogueSessionEventPayload payload) {
        if (payload == null) {
            return DialogueAccessDecision.deny("EVENT_MISSING", "Dialogue event payload is missing");
        }
        return DialogueAccessDecision.allow();
    }
}
