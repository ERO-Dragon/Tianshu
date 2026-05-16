package com.rheinmetal.tianshu.function.ia.gateway;

import com.rheinmetal.tianshu.function.ia.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.function.ia.model.DialogueSession;
import com.rheinmetal.tianshu.function.ia.payload.DialogueArbitrationRequestPayload;
import com.rheinmetal.tianshu.function.ia.payload.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.function.ia.security.DialogueAccessDecision;
import com.rheinmetal.tianshu.function.ia.security.DialogueAccessPolicy;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;

import java.util.Objects;

public final class DialogueMessageGateway {
    private final DialogueProtocolPort protocolPort;
    private final DialogueAccessPolicy accessPolicy;

    public DialogueMessageGateway(DialogueProtocolPort protocolPort, DialogueAccessPolicy accessPolicy) {
        this.protocolPort = Objects.requireNonNull(protocolPort, "protocolPort");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
    }

    public DialogueAccessDecision deliverToOwner(TianshuEnvelope parent, DialogueSession session, DialogueParticipantDescriptor owner, DialogueArbitrationRequestPayload request) {
        DialogueAccessDecision decision = accessPolicy.authorizeDialogueBodyDelivery(session, owner.moduleId(), owner.participantId());
        if (!decision.allowed()) {
            return decision;
        }
        protocolPort.deliverToCapability(parent, owner.routeCapability(), DialogueDeliveryPayload.from(session.sessionId(), request));
        return DialogueAccessDecision.allow();
    }
}
