package com.rheinmetal.tianshu.function.ia.event;

import com.rheinmetal.tianshu.function.ia.model.DialogueReleaseReason;
import com.rheinmetal.tianshu.function.ia.model.DialogueSession;
import com.rheinmetal.tianshu.function.ia.model.DialogueSessionEventType;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;

import java.util.Objects;

public final class DialogueArbitrationEventOrchestrator {
    private final DialogueEventPublisher eventPublisher;

    public DialogueArbitrationEventOrchestrator(DialogueEventPublisher eventPublisher) {
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
    }

    public void publishAccepted(TianshuEnvelope parent, DialogueSession currentSession, boolean ownerChanged, String reasonCode, long nowMillis) {
        if (ownerChanged) {
            eventPublisher.publish(parent, currentSession, DialogueSessionEventType.CONVERSATION_OWNER_CHANGED, DialogueReleaseReason.PREEMPTED, reasonCode, nowMillis);
        }
        eventPublisher.publish(parent, currentSession, DialogueSessionEventType.CONVERSATION_SESSION_STARTED, null, reasonCode, nowMillis);
        eventPublisher.publish(parent, currentSession, DialogueSessionEventType.CONVERSATION_CLAIMED, null, reasonCode, nowMillis);
    }
}
