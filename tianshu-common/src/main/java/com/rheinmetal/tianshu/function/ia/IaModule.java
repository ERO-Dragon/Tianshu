package com.rheinmetal.tianshu.function.ia;

import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.function.ia.claim.DialogueClaimEngine;
import com.rheinmetal.tianshu.function.ia.control.DialogueSessionControlDecision;
import com.rheinmetal.tianshu.function.ia.control.DialogueSessionControlPolicy;
import com.rheinmetal.tianshu.function.ia.diagnostics.DialogueDiagnosticsView;
import com.rheinmetal.tianshu.function.ia.event.DialogueArbitrationEventOrchestrator;
import com.rheinmetal.tianshu.function.ia.event.DialogueEventPublisher;
import com.rheinmetal.tianshu.function.ia.gateway.DialogueMessageGateway;
import com.rheinmetal.tianshu.function.ia.model.DialogueArbitrationDecision;
import com.rheinmetal.tianshu.function.ia.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.function.ia.model.DialogueReleaseReason;
import com.rheinmetal.tianshu.function.ia.model.DialogueSession;
import com.rheinmetal.tianshu.function.ia.model.DialogueSessionControlAction;
import com.rheinmetal.tianshu.function.ia.model.DialogueSessionEventType;
import com.rheinmetal.tianshu.function.ia.payload.DialogueArbitrationRequestPayload;
import com.rheinmetal.tianshu.function.ia.payload.DialogueArbitrationResultPayload;
import com.rheinmetal.tianshu.function.ia.payload.DialogueLlmUsageAuthorizationRequestPayload;
import com.rheinmetal.tianshu.function.ia.payload.DialogueLlmUsageAuthorizationResultPayload;
import com.rheinmetal.tianshu.function.ia.payload.DialogueParticipantRegisterPayload;
import com.rheinmetal.tianshu.function.ia.payload.DialogueParticipantUnregisterPayload;
import com.rheinmetal.tianshu.function.ia.payload.DialogueSessionControlPayload;
import com.rheinmetal.tianshu.function.ia.policy.DialogueArbitrationPolicy;
import com.rheinmetal.tianshu.function.ia.registry.DialogueParticipantRegistry;
import com.rheinmetal.tianshu.function.ia.runtime.DialogueLifecycleSweeper;
import com.rheinmetal.tianshu.function.ia.runtime.DialogueParticipantLifecycleCoordinator;
import com.rheinmetal.tianshu.function.ia.security.DialogueAccessController;
import com.rheinmetal.tianshu.function.ia.security.DialogueAccessDecision;
import com.rheinmetal.tianshu.function.ia.security.DialogueLlmUsageAuthorizationPolicy;
import com.rheinmetal.tianshu.function.ia.session.DialogueSessionStore;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

import java.util.List;
import java.util.Optional;

public final class IaModule implements TianshuManagedModule {
    private final IaProtocolAdapter adapter;
    private final DialogueParticipantRegistry participantRegistry;
    private final DialogueSessionStore sessionStore;
    private final DialogueClaimEngine claimEngine;
    private final DialogueArbitrationPolicy arbitrationPolicy;
    private final DialogueSessionControlPolicy sessionControlPolicy;
    private final DialogueAccessController accessController;
    private final DialogueLlmUsageAuthorizationPolicy llmUsageAuthorizationPolicy;
    private final DialogueEventPublisher eventPublisher;
    private final DialogueArbitrationEventOrchestrator arbitrationEventOrchestrator;
    private final DialogueMessageGateway messageGateway;
    private final DialogueLifecycleSweeper lifecycleSweeper;
    private final DialogueParticipantLifecycleCoordinator participantLifecycleCoordinator;
    private final DialogueDiagnosticsView diagnosticsView;
    private final IaModuleService moduleService;
    private ModuleRuntimeContext runtimeContext;

    public IaModule(ProtocolRuntime runtime) {
        this.adapter = new IaProtocolAdapter(runtime);
        this.participantRegistry = new DialogueParticipantRegistry();
        this.sessionStore = new DialogueSessionStore();
        this.claimEngine = new DialogueClaimEngine();
        this.arbitrationPolicy = new DialogueArbitrationPolicy();
        this.sessionControlPolicy = new DialogueSessionControlPolicy();
        this.accessController = new DialogueAccessController();
        this.llmUsageAuthorizationPolicy = new DialogueLlmUsageAuthorizationPolicy(accessController);
        this.eventPublisher = new DialogueEventPublisher(adapter, accessController);
        this.arbitrationEventOrchestrator = new DialogueArbitrationEventOrchestrator(eventPublisher);
        this.messageGateway = new DialogueMessageGateway(adapter, accessController);
        this.lifecycleSweeper = new DialogueLifecycleSweeper(sessionStore, eventPublisher);
        this.participantLifecycleCoordinator = new DialogueParticipantLifecycleCoordinator(participantRegistry, sessionStore, eventPublisher);
        this.diagnosticsView = new DialogueDiagnosticsView(participantRegistry, sessionStore);
        this.moduleService = new IaModuleService(participantRegistry, diagnosticsView, participantLifecycleCoordinator);
    }

    @Override
    public String moduleId() {
        return IaProtocolAdapter.MODULE_ID;
    }

    @Override
    public void register(ModuleRegistrationContext context) {
        adapter.registerArbitrationCapability(this::handleArbitration);
        adapter.registerParticipantCapability(this::handleParticipantRegister);
        adapter.registerParticipantUnregisterCapability(this::handleParticipantUnregister);
        adapter.registerSessionControlCapability(this::handleSessionControl);
        adapter.registerLlmUsageAuthorizationCapability(this::handleLlmUsageAuthorization);
        context.services().register(DialogueParticipantRegistry.class, participantRegistry);
        context.services().register(DialogueDiagnosticsView.class, diagnosticsView);
        context.services().register(IaModuleService.class, moduleService);
    }

    @Override
    public void prepare(ModuleRuntimeContext context) {
        runtimeContext = context;
        context.runtimeState().capabilities().markReady(IaRuntimeCapabilities.ARBITRATION, moduleId());
    }

    @Override
    public void stop() {
        long now = System.currentTimeMillis();
        participantLifecycleCoordinator.unregisterModule(null, moduleId(), now);
    }

    @Override
    public void destroy() {
        participantRegistry.clear();
        sessionStore.clear();
        if (runtimeContext != null) {
            runtimeContext.runtimeState().capabilities().remove(IaRuntimeCapabilities.ARBITRATION);
        }
        runtimeContext = null;
    }

    private void handleParticipantRegister(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof DialogueParticipantRegisterPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "Dialogue participant payload is invalid", null);
            return;
        }
        participantRegistry.register(payload.descriptor());
        context.complete(envelope.envelopeId());
    }

    private void handleParticipantUnregister(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof DialogueParticipantUnregisterPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "Dialogue participant unregister payload is invalid", null);
            return;
        }
        long now = System.currentTimeMillis();
        if (payload.allParticipants()) {
            participantLifecycleCoordinator.unregisterModule(envelope, payload.moduleId(), now);
        } else {
            participantLifecycleCoordinator.unregisterParticipant(envelope, payload.moduleId(), payload.participantId(), now);
        }
        context.complete(envelope.envelopeId());
    }

    private void handleArbitration(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof DialogueArbitrationRequestPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "Dialogue arbitration payload is invalid", null);
            return;
        }
        long now = System.currentTimeMillis();
        lifecycleSweeper.sweep(envelope, now);
        if (payload.expiredAt(now)) {
            DialogueSession rejected = sessionStore.reject(payload.playerId(), payload.turnId(), now);
            eventPublisher.publish(envelope, rejected, DialogueSessionEventType.CONVERSATION_REJECTED, DialogueReleaseReason.EXPIRED, "REQUEST_EXPIRED", now);
            adapter.respondArbitrationResult(envelope, rejectedResult(payload, rejected, "REQUEST_EXPIRED"));
            context.complete(envelope.envelopeId());
            return;
        }
        List<DialogueParticipantDescriptor> participants = participantRegistry.snapshot();
        Optional<DialogueSession> activeSession = sessionStore.activeForPlayer(payload.playerId(), now);
        DialogueArbitrationDecision decision = arbitrationPolicy.decide(participants, claimEngine.collectLocalClaims(participants, payload), activeSession, now, payload.interactionHints().interactionKeyDown());
        if (!decision.accepted()) {
            DialogueSession rejected = sessionStore.reject(payload.playerId(), payload.turnId(), now);
            eventPublisher.publish(envelope, rejected, DialogueSessionEventType.CONVERSATION_REJECTED, DialogueReleaseReason.REJECTED, decision.reason(), now);
            adapter.respondArbitrationResult(envelope, rejectedResult(payload, rejected, decision.reason()));
            context.complete(envelope.envelopeId());
            return;
        }
        DialogueSession session = claimSession(activeSession, payload, decision.owner(), now);
        arbitrationEventOrchestrator.publishAccepted(envelope, activeSession, session, decision.ownerChanged(), decision.reason(), now);
        DialogueAccessDecision deliveryDecision = messageGateway.deliverToOwner(envelope, session, decision.owner(), payload);
        if (!deliveryDecision.allowed()) {
            DialogueSession released = sessionStore.release(session.sessionId(), DialogueReleaseReason.ACCESS_DENIED, now);
            eventPublisher.publish(envelope, released, DialogueSessionEventType.CONVERSATION_RELEASED, DialogueReleaseReason.ACCESS_DENIED, deliveryDecision.reasonCode(), now);
            context.fail(envelope.envelopeId(), deliveryDecision.reasonCode(), deliveryDecision.message(), null);
            return;
        }
        DialogueSession active = sessionStore.activate(session.sessionId(), now);
        adapter.respondArbitrationResult(envelope, acceptedResult(payload, active, decision.owner(), decision.reason()));
        context.complete(envelope.envelopeId());
    }

    private void handleSessionControl(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof DialogueSessionControlPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "Dialogue session control payload is invalid", null);
            return;
        }
        long now = System.currentTimeMillis();
        lifecycleSweeper.sweep(envelope, now);
        Optional<DialogueSession> optionalSession = sessionStore.find(payload.sessionId());
        if (optionalSession.isEmpty()) {
            context.fail(envelope.envelopeId(), "SESSION_NOT_FOUND", "Dialogue session not found", null);
            return;
        }
        DialogueSession session = optionalSession.get();
        DialogueAccessDecision accessDecision = accessController.authorizeSessionControl(session, payload.requesterModuleId(), payload.requesterParticipantId());
        if (!accessDecision.allowed()) {
            context.fail(envelope.envelopeId(), accessDecision.reasonCode(), accessDecision.message(), null);
            return;
        }
        DialogueSessionControlDecision controlDecision = sessionControlPolicy.decide(session, payload.action(), now);
        if (!controlDecision.allowed()) {
            context.fail(envelope.envelopeId(), controlDecision.reasonCode(), controlDecision.message(), null);
            return;
        }
        if (payload.action() == DialogueSessionControlAction.RENEW) {
            DialogueSession renewed = sessionStore.renew(session.sessionId(), now, now + payload.requestedLeaseMillis());
            eventPublisher.publish(envelope, renewed, DialogueSessionEventType.CONVERSATION_CLAIMED, null, "LEASE_RENEWED", now);
        } else if (payload.action() == DialogueSessionControlAction.INTERRUPT_ACK) {
            DialogueSession interrupted = sessionStore.interrupting(session.sessionId(), now);
            eventPublisher.publish(envelope, interrupted, DialogueSessionEventType.CONVERSATION_INTERRUPTED, null, "INTERRUPT_ACK", now);
        } else {
            DialogueReleaseReason reason = payload.reason() == null ? DialogueReleaseReason.OWNER_COMPLETED : payload.reason();
            DialogueSession released = sessionStore.release(session.sessionId(), reason, now);
            eventPublisher.publish(envelope, released, DialogueSessionEventType.CONVERSATION_RELEASED, reason, payload.action().name(), now);
            eventPublisher.publish(envelope, released, DialogueSessionEventType.CONVERSATION_SESSION_FINISHED, reason, payload.action().name(), now);
        }
        context.complete(envelope.envelopeId());
    }

    private void handleLlmUsageAuthorization(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof DialogueLlmUsageAuthorizationRequestPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "Dialogue LLM usage authorization payload is invalid", null);
            return;
        }
        long now = System.currentTimeMillis();
        lifecycleSweeper.sweep(envelope, now);
        Optional<DialogueSession> optionalSession = sessionStore.find(payload.sessionId());
        if (optionalSession.isEmpty()) {
            adapter.respondLlmUsageAuthorizationResult(envelope, llmUsageAuthorizationResult(payload, null, DialogueAccessDecision.deny("SESSION_NOT_FOUND", "Dialogue session not found")));
            context.complete(envelope.envelopeId());
            return;
        }
        DialogueSession session = optionalSession.get();
        DialogueAccessDecision decision = llmUsageAuthorizationPolicy.authorize(session, payload.requesterModuleId(), payload.requesterParticipantId(), payload.turnId(), now);
        adapter.respondLlmUsageAuthorizationResult(envelope, llmUsageAuthorizationResult(payload, session, decision));
        context.complete(envelope.envelopeId());
    }

    private DialogueSession claimSession(Optional<DialogueSession> activeSession, DialogueArbitrationRequestPayload payload, DialogueParticipantDescriptor owner, long now) {
        if (activeSession.isPresent()) {
            return sessionStore.claimExisting(activeSession.get(), payload.turnId(), owner, now);
        }
        return sessionStore.createClaimed(payload.playerId(), payload.turnId(), owner, now);
    }

    private DialogueArbitrationResultPayload acceptedResult(DialogueArbitrationRequestPayload request, DialogueSession session, DialogueParticipantDescriptor owner, String reason) {
        return new DialogueArbitrationResultPayload(request.requestId(), session.sessionId(), true, owner.moduleId(), owner.participantId(), owner.routeCapability(), "", reason, session.leaseExpireAtMillis());
    }

    private DialogueArbitrationResultPayload rejectedResult(DialogueArbitrationRequestPayload request, DialogueSession session, String reason) {
        return new DialogueArbitrationResultPayload(request.requestId(), session.sessionId(), false, "", "", "", "", reason, session.leaseExpireAtMillis());
    }

    private DialogueLlmUsageAuthorizationResultPayload llmUsageAuthorizationResult(DialogueLlmUsageAuthorizationRequestPayload request, DialogueSession session, DialogueAccessDecision decision) {
        return new DialogueLlmUsageAuthorizationResultPayload(
                request.sessionId(),
                decision.allowed(),
                request.requesterModuleId(),
                request.requesterParticipantId(),
                session == null ? "" : session.ownerModuleId(),
                session == null ? "" : session.ownerParticipantId(),
                decision.reasonCode(),
                decision.message(),
                session == null ? 0L : session.leaseExpireAtMillis()
        );
    }
}
