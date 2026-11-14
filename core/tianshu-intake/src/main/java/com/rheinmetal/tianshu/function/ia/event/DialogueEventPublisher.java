package com.rheinmetal.tianshu.function.ia.event;

import com.rheinmetal.tianshu.api.diagnostics.DiagnosticEvent;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticPrivacy;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSeverity;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink;
import com.rheinmetal.tianshu.function.ia.IaProtocolAdapter;
import com.rheinmetal.tianshu.function.ia.gateway.DialogueProtocolPort;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueReleaseReason;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueSession;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueSessionEventType;
import com.rheinmetal.tianshu.function.ia.contract.DialogueSessionEventPayload;
import com.rheinmetal.tianshu.function.ia.security.DialogueAccessPolicy;
import com.rheinmetal.tianshu.function.ia.security.DialogueAccessDecision;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class DialogueEventPublisher {
    private final DialogueProtocolPort protocolPort;
    private final DialogueAccessPolicy accessPolicy;
    /**
     * The session's diagnostic sink, bound when the module starts.
     *
     * <p>Every session transition passes through this publisher, so one hook here covers start, claim,
     * owner change, completion, rejection and expiry — the lifecycle sweepers included. The publisher
     * never chooses what to say: it reports the transition and the module's chat catalogue words it.</p>
     */
    private volatile DiagnosticSink diagnostics = DiagnosticSink.NOOP;

    public DialogueEventPublisher(DialogueProtocolPort protocolPort, DialogueAccessPolicy accessPolicy) {
        this.protocolPort = Objects.requireNonNull(protocolPort, "protocolPort");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
    }

    /** Binds the sink for the active session; {@link DiagnosticSink#NOOP} releases it. */
    public void bindDiagnostics(DiagnosticSink sink) {
        this.diagnostics = sink == null ? DiagnosticSink.NOOP : sink;
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
        if (!accessDecision.allowed()) {
            return;
        }
        if (parent == null) {
            protocolPort.publishSessionEvent(payload);
        } else {
            protocolPort.publishSessionEvent(parent, payload);
        }
        publishDiagnostic(session, eventType, releaseReason, reasonCode);
    }

    private void publishDiagnostic(DialogueSession session, DialogueSessionEventType eventType,
                                   DialogueReleaseReason releaseReason, String reasonCode) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("sessionId", session.sessionId());
        attributes.put("event", eventType == null ? "" : eventType.name());
        attributes.put("state", session.state() == null ? "" : session.state().name());
        attributes.put("owner", ownerKey(session.ownerModuleId(), session.ownerParticipantId()));
        attributes.put("releaseReason", releaseReason == null ? "" : releaseReason.name());
        attributes.put("reason", reasonCode == null ? "" : reasonCode);
        diagnostics.publish(DiagnosticEvent.now(
                IaProtocolAdapter.MODULE_ID,
                "DIALOGUE_SESSION_EVENT",
                DiagnosticSeverity.INFO,
                DiagnosticPrivacy.REDACTED,
                attributes
        ));
    }

    /** {@code module:participant}, or empty when the transition has no owner. */
    static String ownerKey(String moduleId, String participantId) {
        if (moduleId == null || moduleId.isBlank() || participantId == null || participantId.isBlank()) {
            return "";
        }
        return moduleId + ":" + participantId;
    }
}
