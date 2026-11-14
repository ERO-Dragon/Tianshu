package com.rheinmetal.tianshu.function.ia.gateway;

import com.rheinmetal.tianshu.function.ia.model.DialogueArbitrationInput;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueSession;
import com.rheinmetal.tianshu.function.ia.contract.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.function.ia.security.DialogueAccessDecision;
import com.rheinmetal.tianshu.function.ia.security.DialogueAccessPolicy;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;

import java.util.Objects;

/**
 * Whether the owner can receive this turn, and the delivery itself.
 *
 * <p>The two are separate on purpose: the arbiter asks before it grants a lease, redraws the partner marker
 * or preempts the running turn, so a turn that cannot be delivered never leaves those side effects behind.</p>
 */
public final class DialogueMessageGateway {
    private final DialogueProtocolPort protocolPort;
    private final DialogueAccessPolicy accessPolicy;

    public DialogueMessageGateway(DialogueProtocolPort protocolPort, DialogueAccessPolicy accessPolicy) {
        this.protocolPort = Objects.requireNonNull(protocolPort, "protocolPort");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
    }

    /** Whether the owner may receive the body of this session, without delivering anything. */
    public DialogueAccessDecision authorizeToOwner(DialogueSession session, DialogueParticipantDescriptor owner) {
        DialogueAccessDecision decision = accessPolicy.authorizeDialogueBodyDelivery(
                session, owner.moduleId(), owner.participantId());
        if (!decision.allowed()) {
            return decision;
        }
        if (!protocolPort.hasCapabilityProvider(owner.routeCapability())) {
            return DialogueAccessDecision.deny(
                    "OWNER_CAPABILITY_UNAVAILABLE", "Dialogue owner capability is not currently available");
        }
        return DialogueAccessDecision.allow();
    }

    /**
     * Delivers the turn body, re-checking authorization first.
     *
     * <p>The re-check is not redundant: a provider can be unregistered between the arbiter's authorization
     * query and this call.</p>
     */
    public DialogueAccessDecision deliverToOwner(TianshuEnvelope parent, DialogueSession session, DialogueParticipantDescriptor owner, DialogueArbitrationInput input) {
        DialogueAccessDecision decision = authorizeToOwner(session, owner);
        if (!decision.allowed()) {
            return decision;
        }
        protocolPort.deliverToCapability(parent, owner.routeCapability(), DialogueDeliveryPayload.from(session.sessionId(), input));
        return DialogueAccessDecision.allow();
    }
}