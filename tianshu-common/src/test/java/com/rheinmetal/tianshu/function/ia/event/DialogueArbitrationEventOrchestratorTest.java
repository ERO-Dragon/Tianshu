package com.rheinmetal.tianshu.function.ia.event;

import com.rheinmetal.tianshu.function.ia.gateway.DialogueProtocolPort;
import com.rheinmetal.tianshu.function.ia.model.DialogueReleaseReason;
import com.rheinmetal.tianshu.function.ia.model.DialogueSession;
import com.rheinmetal.tianshu.function.ia.model.DialogueSessionEventType;
import com.rheinmetal.tianshu.function.ia.model.DialogueSessionState;
import com.rheinmetal.tianshu.function.ia.payload.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.function.ia.payload.DialogueSessionEventPayload;
import com.rheinmetal.tianshu.function.ia.security.DialogueAccessController;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DialogueArbitrationEventOrchestratorTest {
    @Test
    void publishesSessionStartedAndClaimedForNewOwner() {
        RecordingPort port = new RecordingPort();
        DialogueArbitrationEventOrchestrator orchestrator = orchestrator(port);
        DialogueSession session = session("session", "module.owner", "participant.owner");

        orchestrator.publishAccepted(null, Optional.empty(), session, false, "OWNER_CLAIMED", 200L);

        assertEquals(2, port.events.size());
        assertEquals(DialogueSessionEventType.CONVERSATION_SESSION_STARTED, port.events.get(0).eventType());
        assertEquals(DialogueSessionEventType.CONVERSATION_CLAIMED, port.events.get(1).eventType());
    }

    @Test
    void publishesInterruptedThenOwnerChangedForPreemption() {
        RecordingPort port = new RecordingPort();
        DialogueArbitrationEventOrchestrator orchestrator = orchestrator(port);
        DialogueSession previous = session("session", "module.old", "participant.old");
        DialogueSession current = session("session", "module.new", "participant.new");

        orchestrator.publishAccepted(null, Optional.of(previous), current, true, "OWNER_PREEMPTED", 200L);

        assertEquals(2, port.events.size());
        assertEquals(DialogueSessionEventType.CONVERSATION_INTERRUPTED, port.events.get(0).eventType());
        assertEquals("module.old", port.events.get(0).ownerModuleId());
        assertEquals(DialogueReleaseReason.PREEMPTED, port.events.get(0).releaseReason());
        assertEquals(DialogueSessionEventType.CONVERSATION_OWNER_CHANGED, port.events.get(1).eventType());
        assertEquals("module.new", port.events.get(1).ownerModuleId());
        assertEquals(DialogueReleaseReason.PREEMPTED, port.events.get(1).releaseReason());
    }

    private DialogueArbitrationEventOrchestrator orchestrator(RecordingPort port) {
        return new DialogueArbitrationEventOrchestrator(new DialogueEventPublisher(port, new DialogueAccessController()));
    }

    private DialogueSession session(String sessionId, String moduleId, String participantId) {
        return new DialogueSession(sessionId, "player", moduleId, participantId, DialogueSessionState.ACTIVE, "turn", 100L, 100L, 1_000L, null);
    }

    private static final class RecordingPort implements DialogueProtocolPort {
        private final List<DialogueSessionEventPayload> events = new ArrayList<>();

        @Override
        public TianshuEnvelope publishSessionEvent(TianshuEnvelope parent, DialogueSessionEventPayload payload) {
            events.add(payload);
            return null;
        }

        @Override
        public TianshuEnvelope deliverToCapability(TianshuEnvelope parent, String capabilityId, DialogueDeliveryPayload payload) {
            return null;
        }
    }
}
