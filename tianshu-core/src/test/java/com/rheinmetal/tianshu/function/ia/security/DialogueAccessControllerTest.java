package com.rheinmetal.tianshu.function.ia.security;

import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueReleaseReason;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueSession;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueSessionEventType;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueSessionState;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueSessionEventPayload;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DialogueAccessControllerTest {
    @Test
    void allowsOwnerToReceiveDialogueBodyAndControlSession() {
        DialogueAccessController controller = new DialogueAccessController();
        DialogueSession session = session();

        DialogueAccessDecision bodyDecision = controller.authorizeDialogueBodyDelivery(session, "module.owner", "participant.owner");
        DialogueAccessDecision controlDecision = controller.authorizeSessionControl(session, "module.owner", "participant.owner");

        assertTrue(bodyDecision.allowed());
        assertTrue(controlDecision.allowed());
    }

    @Test
    void deniesNonOwnerWithStableReasonCode() {
        DialogueAccessController controller = new DialogueAccessController();

        DialogueAccessDecision decision = controller.authorizeSessionControl(session(), "module.other", "participant.other");

        assertFalse(decision.allowed());
        assertEquals("NOT_SESSION_OWNER", decision.reasonCode());
    }

    @Test
    void deniesMissingSession() {
        DialogueAccessController controller = new DialogueAccessController();

        DialogueAccessDecision decision = controller.authorizeDialogueBodyDelivery(null, "module.owner", "participant.owner");

        assertFalse(decision.allowed());
        assertEquals("SESSION_MISSING", decision.reasonCode());
    }

    @Test
    void allowsPublicSessionEventPayload() {
        DialogueAccessController controller = new DialogueAccessController();
        DialogueSessionEventPayload payload = new DialogueSessionEventPayload("s", "p", "m", "part", DialogueSessionState.ACTIVE, DialogueSessionEventType.CONVERSATION_CLAIMED, DialogueReleaseReason.OWNER_COMPLETED, "", 100L);

        assertTrue(controller.authorizePublicEvent(payload).allowed());
    }

    private DialogueSession session() {
        return new DialogueSession("session", "player", "module.owner", "participant.owner", DialogueSessionState.ACTIVE, "turn", 100L, 100L, 1_000L, null);
    }
}
