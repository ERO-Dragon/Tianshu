package com.rheinmetal.tianshu.function.ia.gateway;

import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueContextFrame;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTurnProcessingPolicy;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueArbitrationInput;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueSession;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueSessionState;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueArbitrationRequestPayload;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueOwnerPreviewPayload;
import com.rheinmetal.tianshu.function.ia.security.DialogueAccessDecision;
import com.rheinmetal.tianshu.function.ia.security.DialogueAccessController;
import com.rheinmetal.tianshu.function.ia.security.DialogueAccessPolicy;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueMessageGatewayTest {
    @Test
    void deliversToOwnerWhenAuthorized() {
        RecordingPort port = new RecordingPort();
        DialogueMessageGateway gateway = new DialogueMessageGateway(port, new DialogueAccessController());
        DialogueSession session = session();
        DialogueParticipantDescriptor owner = owner();
        DialogueArbitrationInput request = request();

        DialogueAccessDecision decision = gateway.deliverToOwner(null, session, owner, request);

        assertTrue(decision.allowed());
        assertEquals(1, port.deliverCount);
    }

    @Test
    void deniesWhenNotOwner() {
        RecordingPort port = new RecordingPort();
        DialogueMessageGateway gateway = new DialogueMessageGateway(port, new DialogueAccessController());
        DialogueSession session = session();
        DialogueParticipantDescriptor owner = new DialogueParticipantDescriptor("other", "module.other", "other", 1, List.of(), List.of(), List.of(), "ROUTE", DialogueTurnProcessingPolicy.DEFAULT);

        DialogueAccessDecision decision = gateway.deliverToOwner(null, session, owner, request());

        assertFalse(decision.allowed());
        assertEquals(0, port.deliverCount);
        assertEquals("NOT_SESSION_OWNER", decision.reasonCode());
    }

    @Test
    void doesNotDeliverWhenAccessPolicyDenies() {
        RecordingPort port = new RecordingPort();
        DialogueMessageGateway gateway = new DialogueMessageGateway(port, new DenyingPolicy());

        DialogueAccessDecision decision = gateway.deliverToOwner(null, session(), owner(), request());

        assertFalse(decision.allowed());
        assertEquals(0, port.deliverCount);
        assertEquals("DELIVERY_DENIED", decision.reasonCode());
    }

    private DialogueArbitrationInput request() {
        DialogueArbitrationRequestPayload request = new DialogueArbitrationRequestPayload("r", "module.ir", "player", "1", 9L, "text", "text", List.of(), List.of(), 100L, 200L);
        return DialogueArbitrationInput.from(request, DialogueContextFrame.empty("player"));
    }

    private DialogueSession session() {
        return new DialogueSession("session", "player", "module.owner", "participant.owner", DialogueSessionState.ACTIVE, "turn", 100L, 100L, 1_000L, null);
    }

    private DialogueParticipantDescriptor owner() {
        return new DialogueParticipantDescriptor("participant.owner", "module.owner", "owner", 1, List.of(), List.of(), List.of(), "ROUTE", DialogueTurnProcessingPolicy.DEFAULT);
    }

    private static final class DenyingPolicy implements DialogueAccessPolicy {
        @Override
        public DialogueAccessDecision authorizeDialogueBodyDelivery(DialogueSession session, String moduleId, String participantId) {
            return DialogueAccessDecision.deny("DELIVERY_DENIED", "Delivery denied");
        }

        @Override
        public DialogueAccessDecision authorizeSessionControl(DialogueSession session, String moduleId, String participantId) {
            return DialogueAccessDecision.allow();
        }

        @Override
        public DialogueAccessDecision authorizePublicEvent(com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueSessionEventPayload payload) {
            return DialogueAccessDecision.allow();
        }
    }

    private static final class RecordingPort implements DialogueProtocolPort {
        private int deliverCount;

        @Override
        public TianshuEnvelope publishSessionEvent(TianshuEnvelope parent, com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueSessionEventPayload payload) {
            return null;
        }

        @Override
        public TianshuEnvelope publishOwnerPreview(TianshuEnvelope parent, DialogueOwnerPreviewPayload payload) {
            return null;
        }

        @Override
        public TianshuEnvelope deliverToCapability(TianshuEnvelope parent, String capabilityId, com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueDeliveryPayload payload) {
            deliverCount++;
            return null;
        }
    }
}
