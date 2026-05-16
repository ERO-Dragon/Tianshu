package com.rheinmetal.tianshu.function.ia.security;

import com.rheinmetal.tianshu.function.ia.model.DialogueSession;
import com.rheinmetal.tianshu.function.ia.payload.DialogueSessionEventPayload;

public interface DialogueAccessPolicy {
    DialogueAccessDecision authorizeDialogueBodyDelivery(DialogueSession session, String moduleId, String participantId);

    DialogueAccessDecision authorizeSessionControl(DialogueSession session, String moduleId, String participantId);

    DialogueAccessDecision authorizePublicEvent(DialogueSessionEventPayload payload);
}
