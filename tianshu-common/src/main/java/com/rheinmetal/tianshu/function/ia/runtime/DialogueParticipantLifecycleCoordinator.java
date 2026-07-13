package com.rheinmetal.tianshu.function.ia.runtime;

import com.rheinmetal.tianshu.function.ia.event.DialogueEventPublisher;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueReleaseReason;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueSession;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueSessionEventType;
import com.rheinmetal.tianshu.function.ia.registry.DialogueParticipantRegistry;
import com.rheinmetal.tianshu.function.ia.session.DialogueSessionStore;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class DialogueParticipantLifecycleCoordinator {
    private final DialogueParticipantRegistry participantRegistry;
    private final DialogueSessionStore sessionStore;
    private final DialogueEventPublisher eventPublisher;

    public DialogueParticipantLifecycleCoordinator(DialogueParticipantRegistry participantRegistry, DialogueSessionStore sessionStore, DialogueEventPublisher eventPublisher) {
        this.participantRegistry = Objects.requireNonNull(participantRegistry, "participantRegistry");
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
    }

    public Optional<DialogueParticipantDescriptor> unregisterParticipant(TianshuEnvelope parent, String moduleId, String participantId, long nowMillis) {
        Optional<DialogueParticipantDescriptor> removed = participantRegistry.unregister(moduleId, participantId);
        List<DialogueSession> released = sessionStore.releaseByOwnerParticipant(moduleId, participantId, DialogueReleaseReason.MODULE_UNLOADED, nowMillis);
        publishReleased(parent, released, "PARTICIPANT_UNREGISTERED", nowMillis);
        return removed;
    }

    public List<DialogueParticipantDescriptor> unregisterModule(TianshuEnvelope parent, String moduleId, long nowMillis) {
        List<DialogueParticipantDescriptor> removed = participantRegistry.unregisterModule(moduleId);
        List<DialogueSession> released = sessionStore.releaseByOwnerModule(moduleId, DialogueReleaseReason.MODULE_UNLOADED, nowMillis);
        publishReleased(parent, released, "MODULE_UNREGISTERED", nowMillis);
        return removed;
    }

    private void publishReleased(TianshuEnvelope parent, List<DialogueSession> released, String reasonCode, long nowMillis) {
        if (released == null || released.isEmpty()) {
            return;
        }
        released.forEach(session -> eventPublisher.publish(parent, session, DialogueSessionEventType.CONVERSATION_RELEASED, DialogueReleaseReason.MODULE_UNLOADED, reasonCode, nowMillis));
    }
}
