package com.rheinmetal.tianshu.function.ia.event;

import com.rheinmetal.tianshu.function.ia.gateway.DialogueProtocolPort;
import com.rheinmetal.tianshu.function.ia.model.DialogueReleaseReason;
import com.rheinmetal.tianshu.function.ia.model.DialogueSession;
import com.rheinmetal.tianshu.function.ia.model.DialogueSessionEventType;
import com.rheinmetal.tianshu.function.ia.payload.DialogueSessionEventPayload;
import com.rheinmetal.tianshu.function.ia.security.DialogueAccessPolicy;
import com.rheinmetal.tianshu.function.ia.security.DialogueAccessDecision;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;

import java.util.Objects;

public final class DialogueEventPublisher {
    private final DialogueProtocolPort protocolPort;
    private final DialogueAccessPolicy accessPolicy;

    public DialogueEventPublisher(DialogueProtocolPort protocolPort, DialogueAccessPolicy accessPolicy) {
        this.protocolPort = Objects.requireNonNull(protocolPort, "protocolPort");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
    }

    public void publish(TianshuEnvelope parent, DialogueSession session, DialogueSessionEventType eventType, DialogueReleaseReason releaseReason, String reasonCode, long nowMillis) {
        DialogueSessionEventPayload payload = new DialogueSessionEventPayload(
                session.sessionId(),
                session.playerId(),
                session.ownerModuleId(),
                session.ownerParticipantId(),
                session.state(),
                eventType,
                releaseReason,
                reasonCode,
                nowMillis
        );
        DialogueAccessDecision accessDecision = accessPolicy.authorizePublicEvent(payload);
        if (accessDecision.allowed()) {
            protocolPort.publishSessionEvent(parent, payload);
        }
    }
}
