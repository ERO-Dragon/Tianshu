package com.rheinmetal.tianshu.function.ia.event;

import com.rheinmetal.tianshu.function.ia.gateway.DialogueProtocolPort;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueReleaseReason;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueSession;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueSessionEventType;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueSessionState;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueOwnerPreviewPayload;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueSessionEventPayload;
import com.rheinmetal.tianshu.function.ia.security.DialogueAccessController;
import com.rheinmetal.tianshu.function.ia.security.DialogueAccessDecision;
import com.rheinmetal.tianshu.function.ia.security.DialogueAccessPolicy;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DialogueEventPublisherTest {
    @Test
    void publishesAuthorizedPublicSessionEvent() {
        RecordingPort port = new RecordingPort();
        DialogueEventPublisher publisher = new DialogueEventPublisher(port, new DialogueAccessController());

        publisher.publish(null, session(), DialogueSessionEventType.CONVERSATION_CLAIMED, null, "CLAIMED", 200L);

        assertEquals(1, port.eventCount);
    }

    @Test
    void skipsSessionEventWhenAccessPolicyDenies() {
        RecordingPort port = new RecordingPort();
        DialogueEventPublisher publisher = new DialogueEventPublisher(port, new DenyingPolicy());

        publisher.publish(null, session(), DialogueSessionEventType.CONVERSATION_RELEASED, DialogueReleaseReason.ACCESS_DENIED, "DENIED", 200L);

        assertEquals(0, port.eventCount);
    }

    private DialogueSession session() {
        return new DialogueSession("session", "player", "module.owner", "participant.owner", DialogueSessionState.ACTIVE, "turn", 100L, 100L, 1_000L, null);
    }

    private static final class DenyingPolicy implements DialogueAccessPolicy {
        @Override
        public DialogueAccessDecision authorizeDialogueBodyDelivery(DialogueSession session, String moduleId, String participantId) {
            return DialogueAccessDecision.allow();
        }

        @Override
        public DialogueAccessDecision authorizeSessionControl(DialogueSession session, String moduleId, String participantId) {
            return DialogueAccessDecision.allow();
        }

        @Override
        public DialogueAccessDecision authorizePublicEvent(DialogueSessionEventPayload payload) {
            return DialogueAccessDecision.deny("EVENT_DENIED", "Event publication denied");
        }
    }

    private static final class RecordingPort implements DialogueProtocolPort {
        private int eventCount;

        @Override
        public boolean hasCapabilityProvider(String capabilityId) {
            return true;
        }

        @Override
        public TianshuEnvelope publishSessionEvent(TianshuEnvelope parent, DialogueSessionEventPayload payload) {
            eventCount++;
            return null;
        }

        @Override
        public TianshuEnvelope publishOwnerPreview(TianshuEnvelope parent, DialogueOwnerPreviewPayload payload) {
            return null;
        }

        @Override
        public TianshuEnvelope deliverToCapability(TianshuEnvelope parent, String capabilityId, DialogueDeliveryPayload payload) {
            return null;
        }
    }
}
