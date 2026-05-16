package com.rheinmetal.tianshu.function.ia.runtime;

import com.rheinmetal.tianshu.function.ia.event.DialogueEventPublisher;
import com.rheinmetal.tianshu.function.ia.model.DialogueSession;
import com.rheinmetal.tianshu.function.ia.model.DialogueSessionEventType;
import com.rheinmetal.tianshu.function.ia.session.DialogueSessionStore;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;

import java.util.List;
import java.util.Objects;

public final class DialogueLifecycleSweeper {
    private final DialogueSessionStore sessionStore;
    private final DialogueEventPublisher eventPublisher;

    public DialogueLifecycleSweeper(DialogueSessionStore sessionStore, DialogueEventPublisher eventPublisher) {
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
    }

    public List<DialogueSession> sweep(TianshuEnvelope parent, long nowMillis) {
        List<DialogueSession> expired = sessionStore.expireOverdue(nowMillis);
        expired.forEach(session -> eventPublisher.publish(parent, session, DialogueSessionEventType.CONVERSATION_EXPIRED, session.releaseReason(), "LEASE_EXPIRED", nowMillis));
        return expired;
    }
}
