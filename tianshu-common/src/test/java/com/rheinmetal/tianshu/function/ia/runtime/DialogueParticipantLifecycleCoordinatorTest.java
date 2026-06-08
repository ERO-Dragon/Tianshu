package com.rheinmetal.tianshu.function.ia.runtime;

import com.rheinmetal.tianshu.function.ia.event.DialogueEventPublisher;
import com.rheinmetal.tianshu.function.ia.gateway.DialogueProtocolPort;
import com.rheinmetal.tianshu.function.ia.model.DialogueTurnProcessingPolicy;
import com.rheinmetal.tianshu.function.ia.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.function.ia.model.DialogueSession;
import com.rheinmetal.tianshu.function.ia.model.DialogueSessionState;
import com.rheinmetal.tianshu.function.ia.payload.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.function.ia.payload.DialogueOwnerPreviewPayload;
import com.rheinmetal.tianshu.function.ia.payload.DialogueSessionEventPayload;
import com.rheinmetal.tianshu.function.ia.registry.DialogueParticipantRegistry;
import com.rheinmetal.tianshu.function.ia.security.DialogueAccessController;
import com.rheinmetal.tianshu.function.ia.session.DialogueSessionStore;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueParticipantLifecycleCoordinatorTest {
    @Test
    void unregisterParticipantReleasesOnlySessionsOwnedByThatParticipant() {
        DialogueParticipantRegistry registry = new DialogueParticipantRegistry();
        DialogueSessionStore store = new DialogueSessionStore();
        RecordingPort port = new RecordingPort();
        DialogueParticipantLifecycleCoordinator coordinator = coordinator(registry, store, port);
        DialogueParticipantDescriptor first = descriptor("module.owner", "participant.one");
        DialogueParticipantDescriptor second = descriptor("module.owner", "participant.two");
        registry.register(first);
        registry.register(second);
        DialogueSession firstSession = store.createClaimed("player.one", "turn.one", first, 100L);
        DialogueSession secondSession = store.createClaimed("player.two", "turn.two", second, 100L);

        coordinator.unregisterParticipant(null, "module.owner", "participant.one", 200L);

        assertTrue(registry.find("module.owner", "participant.one").isEmpty());
        assertTrue(registry.find("module.owner", "participant.two").isPresent());
        assertEquals(DialogueSessionState.RELEASED, store.find(firstSession.sessionId()).orElseThrow().state());
        assertEquals(DialogueSessionState.CLAIMED, store.find(secondSession.sessionId()).orElseThrow().state());
        assertEquals(1, port.events.size());
        assertEquals(firstSession.sessionId(), port.events.get(0).sessionId());
    }

    @Test
    void unregisterModuleReleasesAllOwnedSessionsAndPublishesEvents() {
        DialogueParticipantRegistry registry = new DialogueParticipantRegistry();
        DialogueSessionStore store = new DialogueSessionStore();
        RecordingPort port = new RecordingPort();
        DialogueParticipantLifecycleCoordinator coordinator = coordinator(registry, store, port);
        DialogueParticipantDescriptor first = descriptor("module.owner", "participant.one");
        DialogueParticipantDescriptor second = descriptor("module.owner", "participant.two");
        DialogueParticipantDescriptor other = descriptor("module.other", "participant.other");
        registry.register(first);
        registry.register(second);
        registry.register(other);
        DialogueSession firstSession = store.createClaimed("player.one", "turn.one", first, 100L);
        DialogueSession secondSession = store.createClaimed("player.two", "turn.two", second, 100L);
        DialogueSession otherSession = store.createClaimed("player.other", "turn.other", other, 100L);

        coordinator.unregisterModule(null, "module.owner", 200L);

        assertTrue(registry.find("module.owner", "participant.one").isEmpty());
        assertTrue(registry.find("module.owner", "participant.two").isEmpty());
        assertTrue(registry.find("module.other", "participant.other").isPresent());
        assertEquals(DialogueSessionState.RELEASED, store.find(firstSession.sessionId()).orElseThrow().state());
        assertEquals(DialogueSessionState.RELEASED, store.find(secondSession.sessionId()).orElseThrow().state());
        assertEquals(DialogueSessionState.CLAIMED, store.find(otherSession.sessionId()).orElseThrow().state());
        assertEquals(2, port.events.size());
    }

    private DialogueParticipantLifecycleCoordinator coordinator(DialogueParticipantRegistry registry, DialogueSessionStore store, RecordingPort port) {
        return new DialogueParticipantLifecycleCoordinator(registry, store, new DialogueEventPublisher(port, new DialogueAccessController()));
    }

    private DialogueParticipantDescriptor descriptor(String moduleId, String participantId) {
        return new DialogueParticipantDescriptor(participantId, moduleId, participantId, 1, List.of(), List.of(), List.of(), "ROUTE", DialogueTurnProcessingPolicy.DEFAULT);
    }

    private static final class RecordingPort implements DialogueProtocolPort {
        private final List<DialogueSessionEventPayload> events = new ArrayList<>();

        @Override
        public TianshuEnvelope publishSessionEvent(TianshuEnvelope parent, DialogueSessionEventPayload payload) {
            events.add(payload);
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
