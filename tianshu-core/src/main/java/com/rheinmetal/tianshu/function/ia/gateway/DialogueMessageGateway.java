package com.rheinmetal.tianshu.function.ia.gateway;

import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueArbitrationInput;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueSession;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueDeliveryPayload;
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

    public DialogueAccessDecision deliverToOwner(TianshuEnvelope parent, DialogueSession session, DialogueParticipantDescriptor owner, DialogueArbitrationInput input) {
        DialogueAccessDecision decision = accessPolicy.authorizeDialogueBodyDelivery(session, owner.moduleId(), owner.participantId());
        if (!decision.allowed()) {
            return decision;
        }
        if (!protocolPort.hasCapabilityProvider(owner.routeCapability())) {
            return DialogueAccessDecision.deny("OWNER_CAPABILITY_UNAVAILABLE", "Dialogue owner capability is not currently available");
        }
        protocolPort.deliverToCapability(parent, owner.routeCapability(), DialogueDeliveryPayload.from(session.sessionId(), input));
        return DialogueAccessDecision.allow();
    }
}
