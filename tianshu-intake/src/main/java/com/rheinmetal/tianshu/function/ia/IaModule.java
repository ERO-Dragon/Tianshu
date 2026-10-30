package com.rheinmetal.tianshu.function.ia;

import com.rheinmetal.tianshu.api.diagnostics.DiagnosticEvent;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticPrivacy;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSeverity;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.function.ia.claim.DialogueClaimEngine;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueContextFrame;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueContextSnapshot;
import com.rheinmetal.tianshu.function.ia.context.DialogueInteractionContextMapper;
import com.rheinmetal.tianshu.function.ia.control.DialogueSessionControlDecision;
import com.rheinmetal.tianshu.function.ia.control.DialogueSessionControlPolicy;
import com.rheinmetal.tianshu.function.ia.diagnostics.DialogueDiagnosticsView;
import com.rheinmetal.tianshu.function.ia.event.DialogueArbitrationEventOrchestrator;
import com.rheinmetal.tianshu.function.ia.event.DialogueEventPublisher;
import com.rheinmetal.tianshu.function.ia.gateway.DialogueMessageGateway;
import com.rheinmetal.tianshu.function.ia.model.DialogueArbitrationInput;
import com.rheinmetal.tianshu.function.ia.partner.DialoguePartnerTracker;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueArbitrationDecision;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaim;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueReleaseReason;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueSession;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueSessionControlAction;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueSessionEventType;
import com.rheinmetal.tianshu.function.ia.contract.DialogueArbitrationRequestPayload;
import com.rheinmetal.tianshu.function.ia.contract.DialogueArbitrationResultPayload;
import com.rheinmetal.tianshu.function.ia.contract.DialogueParticipantRegisterPayload;
import com.rheinmetal.tianshu.function.ia.contract.DialogueParticipantUnregisterPayload;
import com.rheinmetal.tianshu.function.ia.contract.DialogueSessionControlPayload;
import com.rheinmetal.tianshu.function.ia.policy.DialogueArbitrationPolicy;
import com.rheinmetal.tianshu.function.ia.policy.DialogueDefaultOwnerResolver;
import com.rheinmetal.tianshu.function.ia.registry.DialogueParticipantContractValidator;
import com.rheinmetal.tianshu.function.ia.registry.DialogueParticipantRegistry;
import com.rheinmetal.tianshu.function.ia.runtime.DialogueTriggerSynchronizer;
import com.rheinmetal.tianshu.function.ia.runtime.DialogueLifecycleSweeper;
import com.rheinmetal.tianshu.function.ia.runtime.DialogueParticipantLifecycleCoordinator;
import com.rheinmetal.tianshu.function.ia.security.DialogueAccessController;
import com.rheinmetal.tianshu.function.ia.security.DialogueAccessDecision;
import com.rheinmetal.tianshu.function.ia.session.DialogueContextFreezeStore;
import com.rheinmetal.tianshu.function.ia.session.DialogueSessionStore;
import com.rheinmetal.tianshu.function.ia.settings.IaDefaultOwnerSettings;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.function.ia.contract.AsrSpeechActivityPayload;
import com.rheinmetal.tianshu.function.ia.contract.IrResultPayload;
import com.rheinmetal.tianshu.function.presence.payload.PresenceInteractionContextPayload;
import com.rheinmetal.tianshu.function.presence.payload.PresencePartnerPayload;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;
import com.rheinmetal.tianshu.protocol.registry.ValidationResult;
import com.rheinmetal.tianshu.protocol.status.ModuleStatuses;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class IaModule implements TianshuManagedModule {
    private static final Duration SPEECH_CONTEXT_RETENTION = Duration.ofSeconds(30L);

    private final IaProtocolAdapter adapter;
    private final DialogueParticipantRegistry participantRegistry;
    private final DialogueSessionStore sessionStore;
    private final DialogueContextFreezeStore contextFreezeStore;
    private final DialogueClaimEngine claimEngine;
    private final DialogueArbitrationPolicy arbitrationPolicy;
    private final DialogueSessionControlPolicy sessionControlPolicy;
    private final DialogueAccessController accessController;
    private final DialogueEventPublisher eventPublisher;
    private final DialogueArbitrationEventOrchestrator arbitrationEventOrchestrator;
    private final DialogueMessageGateway messageGateway;
    private final DialogueLifecycleSweeper lifecycleSweeper;
    private final DialogueParticipantLifecycleCoordinator participantLifecycleCoordinator;
    private final DialogueParticipantContractValidator participantContractValidator;
    private final DialogueDiagnosticsView diagnosticsView;
    private final IaModuleService moduleService;
    private final Map<String, PresencePartnerPayload> partnerPayloads = new ConcurrentHashMap<>();
    private final DialogueInteractionContextMapper interactionContextMapper = new DialogueInteractionContextMapper();
    private final DialoguePartnerTracker partnerTracker = new DialoguePartnerTracker();
    private final IaDefaultOwnerSettings defaultOwnerSettings;
    private final DialogueDefaultOwnerResolver defaultOwnerResolver;
    private final ModuleRuntimeAccess runtime;
    private final DialogueTriggerSynchronizer triggerSynchronizer;
    private final IrResultArbitrationMapper irResultMapper;
    private final AtomicLong runtimeGeneration = new AtomicLong(1L);
    /**
     * The local player's latest interaction digest; one slot, not per-player.
     *
     * <p>{@code tianshu-intake} is installed on the client only — the server jar carries the host and no module
     * assembly, so a runtime never sees more than the one player sitting at this keyboard. Keep it that way: if
     * the arbiter is ever hosted for several players at once, this has to become a per-player map first.</p>
     */
    private volatile PresenceInteractionContextPayload interactionContext;
    private ProtocolTaskHandle partnerExpiryHandle;
    private volatile long partnerExpiryGeneration;
    private ModuleRuntimeContext runtimeContext;
    private volatile boolean runtimeAccepting = true;
    private volatile long lifecycleStatusGeneration;
    private volatile boolean lifecycleActive;
    private com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink diagnostics = com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink.NOOP;

    public IaModule(ModuleRuntimeAccess runtime) {
        this(runtime, IaDefaultOwnerSettings.UNCONFIGURED);
    }

    public IaModule(ModuleRuntimeAccess runtime, IaDefaultOwnerSettings defaultOwnerSettings) {
        this.runtime = runtime;
        this.defaultOwnerSettings = defaultOwnerSettings == null ? IaDefaultOwnerSettings.UNCONFIGURED : defaultOwnerSettings;
        this.defaultOwnerResolver = new DialogueDefaultOwnerResolver();
        this.adapter = new IaProtocolAdapter(runtime);
        this.participantRegistry = new DialogueParticipantRegistry();
        this.sessionStore = new DialogueSessionStore();
        this.contextFreezeStore = new DialogueContextFreezeStore(SPEECH_CONTEXT_RETENTION);
        this.claimEngine = new DialogueClaimEngine();
        this.arbitrationPolicy = new DialogueArbitrationPolicy();
        this.sessionControlPolicy = new DialogueSessionControlPolicy();
        this.accessController = new DialogueAccessController();
        this.eventPublisher = new DialogueEventPublisher(adapter, accessController);
        this.arbitrationEventOrchestrator = new DialogueArbitrationEventOrchestrator(eventPublisher);
        this.messageGateway = new DialogueMessageGateway(adapter, accessController);
        this.lifecycleSweeper = new DialogueLifecycleSweeper(sessionStore, eventPublisher);
        this.participantLifecycleCoordinator = new DialogueParticipantLifecycleCoordinator(participantRegistry, sessionStore, eventPublisher);
        this.participantContractValidator = new DialogueParticipantContractValidator(runtime);
        this.diagnosticsView = new DialogueDiagnosticsView(participantRegistry, sessionStore);
        this.moduleService = new IaModuleService(participantRegistry, diagnosticsView, participantLifecycleCoordinator, participantContractValidator,
                this::handleParticipantsChanged, this::refreshPartnersOnDefaultOwnerChange);
        this.triggerSynchronizer = new DialogueTriggerSynchronizer();
        this.irResultMapper = new IrResultArbitrationMapper();
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
        adapter.subscribeAsrSpeechActivity(this::handleAsrSpeechActivity);
        adapter.subscribeIrResult(this::handleIrResult);
        adapter.subscribeInteractionContext(this::handleInteractionContext);
        context.services().register(DialogueParticipantRegistry.class, participantRegistry);
        context.services().register(DialogueDiagnosticsView.class, diagnosticsView);
        context.services().register(IaModuleService.class, moduleService);
    }

    @Override
    public synchronized void prepare(ModuleRuntimeContext context) {
        ModuleRuntimeContext nextContext = Objects.requireNonNull(context, "context");
        lifecycleStatusGeneration = runtimeGeneration.get() + 1L;
        runtimeGeneration.incrementAndGet();
        lifecycleActive = true;
        adapter.publishModuleStatus(ModuleStatuses.lifecycleStarting(moduleId(), lifecycleStatusGeneration));
        if (runtimeContext != null) {
            deactivateRuntime();
        }
        runtimeContext = nextContext;
        runtimeAccepting = true;
        diagnostics = nextContext.diagnostics();
        triggerSynchronizer.bind(nextContext.triggers(), participantRegistry.snapshot());
        nextContext.runtimeState().capabilities().markReady(IaRuntimeCapabilities.ARBITRATION, moduleId());
        adapter.publishModuleStatus(ModuleStatuses.lifecycleReady(moduleId(), lifecycleStatusGeneration));
    }

    @Override
    public synchronized void stop() {
        deactivateRuntime();
        long now = System.currentTimeMillis();
        participantLifecycleCoordinator.unregisterModule(null, moduleId(), now);
        if (lifecycleActive) {
            lifecycleActive = false;
            adapter.publishModuleStatus(ModuleStatuses.lifecycleStopped(moduleId(), lifecycleStatusGeneration));
        }
    }

    @Override
    public synchronized void destroy() {
        deactivateRuntime();
        participantRegistry.clear();
        sessionStore.clear();
        contextFreezeStore.clear();
        partnerPayloads.clear();
        partnerTracker.clear();
        interactionContext = null;
        runtimeContext = null;
    }

    private void deactivateRuntime() {
        runtimeAccepting = false;
        runtimeGeneration.incrementAndGet();
        cancelPartnerExpiry();
        partnerTracker.clear();
        partnerPayloads.clear();
        interactionContext = null;
        triggerSynchronizer.unbind();
        if (runtimeContext != null) {
            runtimeContext.runtimeState().capabilities().remove(IaRuntimeCapabilities.ARBITRATION);
        }
        diagnostics = com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink.NOOP;
    }

    private void handleParticipantRegister(TianshuEnvelope envelope, ProtocolContext context) {
        if (rejectWhenInactive(envelope, context)) {
            return;
        }
        if (!(envelope.payload() instanceof DialogueParticipantRegisterPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "Dialogue participant payload is invalid", null);
            return;
        }
        ValidationResult validation = participantContractValidator.validate(payload.descriptor());
        if (!validation.accepted()) {
            context.fail(envelope.envelopeId(), validation.code(), validation.message(), null);
            return;
        }
        participantRegistry.register(payload.descriptor());
        handleParticipantsChanged();
        context.complete(envelope.envelopeId());
    }

    private void handleParticipantUnregister(TianshuEnvelope envelope, ProtocolContext context) {
        if (rejectWhenInactive(envelope, context)) {
            return;
        }
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
        handleParticipantsChanged();
        context.complete(envelope.envelopeId());
    }

    private void handleArbitration(TianshuEnvelope envelope, ProtocolContext context) {
        if (rejectWhenInactive(envelope, context)) {
            return;
        }
        if (!(envelope.payload() instanceof DialogueArbitrationRequestPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "Dialogue arbitration payload is invalid", null);
            return;
        }
        processArbitration(envelope, context, payload);
    }

    private void handleIrResult(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof IrResultPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "IR result payload is invalid", null);
            return;
        }
        // Reaching this handler means the turn now has an outcome, whatever it is: the arbiter is the one that
        // closes the listening state the speech module opened, so the player is never marked as still speaking after
        // the turn has been resolved (or rejected, or dropped because this module is not running).
        endListeningTurn(payload.sessionId());
        if (rejectWhenInactive(envelope, context)) {
            return;
        }
        if (payload.repairedText().isBlank() && payload.normalizedText().isBlank()) {
            context.complete(envelope.envelopeId());
            return;
        }
        processArbitration(envelope, context, irResultMapper.map(envelope, payload));
    }

    private void endListeningTurn(long sourceSessionId) {
        if (sourceSessionId > 0L) {
            adapter.publishListeningEnded(sourceSessionId);
        }
    }

    private void processArbitration(TianshuEnvelope envelope, ProtocolContext context, DialogueArbitrationRequestPayload payload) {
        if (rejectWhenInactive(envelope, context)) {
            return;
        }
        long now = System.currentTimeMillis();
        lifecycleSweeper.sweep(envelope, now);
        if (payload.expiredAt(now)) {
            DialogueSession rejected = sessionStore.reject(payload.playerId(), payload.turnId(), now);
            eventPublisher.publish(envelope, rejected, DialogueSessionEventType.CONVERSATION_REJECTED, DialogueReleaseReason.EXPIRED, "REQUEST_EXPIRED", now);
            eventPublisher.publish(envelope, rejected, DialogueSessionEventType.CONVERSATION_SESSION_FINISHED, DialogueReleaseReason.EXPIRED, "REQUEST_EXPIRED", now);
            respondArbitrationResultIfRequested(envelope, rejectedResult(payload, rejected, "REQUEST_EXPIRED"));
            context.complete(envelope.envelopeId());
            return;
        }
        List<DialogueParticipantDescriptor> participants = participantRegistry.snapshot();
        if (participants.isEmpty()) {
            continueArbitration(
                    envelope,
                    context,
                    payload,
                    DialogueContextFrame.empty(payload.playerId()),
                    now
            );
            return;
        }
        // The turn runs against the frame frozen when the player started speaking. A turn that arrives without a
        // freeze (an injected request, a session that began before the speech step) falls back to the newest digest,
        // which is the same frame, just not pinned to that moment.
        DialogueContextFrame frame = frozenContextFor(payload, now).orElseGet(this::currentInteractionFrame);
        continueArbitration(envelope, context, payload, frame, now);
    }

    private void continueArbitration(
            TianshuEnvelope envelope,
            ProtocolContext context,
            DialogueArbitrationRequestPayload payload,
            DialogueContextFrame contextFrame,
            long now
    ) {
        if (rejectWhenInactive(envelope, context)) {
            return;
        }
        lifecycleSweeper.sweep(envelope, now);
        if (payload.expiredAt(now)) {
            DialogueSession rejected = sessionStore.reject(payload.playerId(), payload.turnId(), now);
            eventPublisher.publish(envelope, rejected, DialogueSessionEventType.CONVERSATION_REJECTED, DialogueReleaseReason.EXPIRED, "REQUEST_EXPIRED", now);
            eventPublisher.publish(envelope, rejected, DialogueSessionEventType.CONVERSATION_SESSION_FINISHED, DialogueReleaseReason.EXPIRED, "REQUEST_EXPIRED", now);
            respondArbitrationResultIfRequested(envelope, rejectedResult(payload, rejected, "REQUEST_EXPIRED"));
            context.complete(envelope.envelopeId());
            return;
        }
        List<DialogueParticipantDescriptor> participants = participantRegistry.snapshot();
        if (participants.isEmpty()) {
            DialogueArbitrationInput input = DialogueArbitrationInput.from(
                    payload,
                    withPlayerId(contextFrame, payload.playerId())
            );
            completeArbitration(
                    envelope,
                    context,
                    payload,
                    input,
                    List.of(),
                    Optional.empty(),
                    List.of(),
                    now
            );
            return;
        }
        Optional<DialogueSession> activeSession = sessionStore.activeForPlayer(payload.playerId(), now);
        DialogueArbitrationInput input = DialogueArbitrationInput.from(payload, withPlayerId(contextFrame, payload.playerId()));
        List<DialogueClaim> hardClaims = claimEngine.collectLocalClaims(participants, input);
        completeArbitration(envelope, context, payload, input, participants, activeSession, hardClaims, now);
    }

    private void completeArbitration(
            TianshuEnvelope envelope,
            ProtocolContext context,
            DialogueArbitrationRequestPayload payload,
            DialogueArbitrationInput input,
            List<DialogueParticipantDescriptor> participants,
            Optional<DialogueSession> activeSession,
            List<DialogueClaim> hardClaims,
            long now
    ) {
        DialogueArbitrationDecision decision = arbitrationPolicy.decide(
                participants, hardClaims, partnerTracker.holderParticipantId(now), resolveDefaultOwner(participants));
        diagnostics.publish(DiagnosticEvent.now(
                moduleId(),
                decision.accepted() ? "ARBITRATION_ACCEPTED" : "ARBITRATION_REJECTED",
                decision.accepted() ? DiagnosticSeverity.INFO : DiagnosticSeverity.DEBUG,
                DiagnosticPrivacy.REDACTED,
                Map.of(
                        "requestId", payload.requestId(),
                        "playerId", payload.playerId(),
                        "owner", decision.owner() == null ? "" : decision.owner().participantId(),
                        "reason", decision.reason() == null ? "" : decision.reason()
                )
        ));
        if (!decision.accepted()) {
            publishPartner(PresencePartnerPayload.empty(payload.playerId(), now));
            DialogueSession rejected = sessionStore.reject(payload.playerId(), payload.turnId(), now);
            eventPublisher.publish(envelope, rejected, DialogueSessionEventType.CONVERSATION_REJECTED, DialogueReleaseReason.REJECTED, decision.reason(), now);
            eventPublisher.publish(envelope, rejected, DialogueSessionEventType.CONVERSATION_SESSION_FINISHED, DialogueReleaseReason.REJECTED, decision.reason(), now);
            respondArbitrationResultIfRequested(envelope, rejectedResult(payload, rejected, decision.reason()));
            context.complete(envelope.envelopeId());
            return;
        }
        boolean leaseContinued = "LEASE_CONTINUED".equals(decision.reason());
        if (decision.claim() != null || leaseContinued) {
            partnerTracker.grant(decision.owner(), now);
        } else {
            partnerTracker.clear();
        }
        publishPartner(partnerFor(payload.playerId(), decision.owner(), decision.claim() == null && !leaseContinued, now));
        schedulePartnerExpiry(payload.playerId(), now);
        DialogueSession session = claimSession(payload, decision.owner(), now);
        releasePreviousTurnIfPresent(envelope, activeSession, session, now);
        arbitrationEventOrchestrator.publishAccepted(envelope, session, ownerChanged(activeSession, decision.owner()), decision.reason(), now);
        DialogueAccessDecision deliveryDecision = messageGateway.deliverToOwner(envelope, session, decision.owner(), input);
        if (!deliveryDecision.allowed()) {
            DialogueSession released = sessionStore.release(session.sessionId(), DialogueReleaseReason.ACCESS_DENIED, now);
            eventPublisher.publish(envelope, released, DialogueSessionEventType.CONVERSATION_RELEASED, DialogueReleaseReason.ACCESS_DENIED, deliveryDecision.reasonCode(), now);
            eventPublisher.publish(envelope, released, DialogueSessionEventType.CONVERSATION_SESSION_FINISHED, DialogueReleaseReason.ACCESS_DENIED, deliveryDecision.reasonCode(), now);
            context.fail(envelope.envelopeId(), deliveryDecision.reasonCode(), deliveryDecision.message(), null);
            return;
        }
        DialogueSession active = sessionStore.activate(session.sessionId(), now);
        respondArbitrationResultIfRequested(envelope, acceptedResult(payload, active, decision.owner(), decision.reason()));
        context.complete(envelope.envelopeId());
    }

    private void handleAsrSpeechActivity(TianshuEnvelope envelope, ProtocolContext context) {
        if (rejectWhenInactive(envelope, context)) {
            return;
        }
        if (!(envelope.payload() instanceof AsrSpeechActivityPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "ASR speech activity payload is invalid", null);
            return;
        }
        long now = payload.occurredAtMillis() > 0L ? payload.occurredAtMillis() : System.currentTimeMillis();
        if (payload.speaking()) {
            contextFreezeStore.freeze(payload.sessionId(), currentInteractionFrame(), now);
        } else {
            contextFreezeStore.markEnded(payload.sessionId(), now);
        }
        context.complete(envelope.envelopeId());
    }

    /**
     * The frame a turn is arbitrated against: the newest interaction digest, never a fresh capture.
     *
     * <p>The marker on screen was computed from this same digest, so pinning the turn to it is what keeps "the
     * participant shown" and "the participant that gets the turn" from drifting apart. A capture taken at this
     * instant would be fresher than the screen and could therefore name somebody the player is not looking at.</p>
     *
     * <p>Before the first push of a session there is no digest, and the frame is empty — which is also exactly
     * what the screen shows at that moment.</p>
     */
    private DialogueContextFrame currentInteractionFrame() {
        PresenceInteractionContextPayload digest = interactionContext;
        return digest == null ? DialogueContextFrame.empty("") : interactionContextMapper.toFrame(digest);
    }

    private Optional<DialogueContextFrame> frozenContextFor(DialogueArbitrationRequestPayload payload, long now) {
        contextFreezeStore.sweep(now);
        return contextFreezeStore.consume(payload.sourceSessionId(), now)
                .map(frame -> withPlayerId(frame, payload.playerId()));
    }

    private void handleParticipantsChanged() {
        triggerSynchronizer.synchronize(participantRegistry.snapshot());
        refreshPartners(System.currentTimeMillis());
    }

    private boolean rejectWhenInactive(TianshuEnvelope envelope, ProtocolContext context) {
        if (runtimeAccepting) {
            return false;
        }
        if (context != null && envelope != null) {
            context.complete(envelope.envelopeId());
        }
        return true;
    }

    private DialogueContextFrame withPlayerId(DialogueContextFrame frame, String playerId) {
        DialogueContextFrame effectiveFrame = frame == null ? DialogueContextFrame.empty(playerId) : frame;
        DialogueContextSnapshot snapshot = effectiveFrame.contextSnapshot();
        return new DialogueContextFrame(
                effectiveFrame.interactionHints(),
                new DialogueContextSnapshot(
                        playerId,
                        snapshot.dimensionId(),
                        snapshot.entityRefs(),
                        snapshot.equippedItemIds(),
                        snapshot.facts()
                ),
                // The names travel with the frame: they are what a delivery shows instead of registry ids.
                effectiveFrame.displayNames()
        );
    }

    private void respondArbitrationResultIfRequested(TianshuEnvelope envelope, DialogueArbitrationResultPayload result) {
        if (envelope.header().packetType() == PacketType.REQUEST) {
            adapter.respondArbitrationResult(envelope, result);
        }
    }

    private void handleInteractionContext(TianshuEnvelope envelope, ProtocolContext context) {
        if (envelope.payload() instanceof PresenceInteractionContextPayload payload) {
            interactionContext = payload;
            refreshPartner(payload.playerId(), System.currentTimeMillis());
        }
        context.complete(envelope.envelopeId());
    }

    /**
     * Recomputes the marker from the latest digest without a round having happened.
     *
     * <p>The order is the same one the arbiter uses: a context claim is the pre-winner, a live lease keeps the
     * previous winner, and otherwise the default owner is displayed. A lease holder that also happens to be the
     * pre-winner keeps its lease fields, so the countdown does not disappear just because the player looked at it.</p>
     */
    private synchronized void refreshPartner(String playerId, long now) {
        if (playerId == null || playerId.isBlank()) {
            return;
        }
        List<DialogueParticipantDescriptor> participants = participantRegistry.snapshot();
        pruneLease(participants, now);
        DialogueContextFrame frame = interactionContext != null && playerId.equals(interactionContext.playerId())
                ? interactionContextMapper.toFrame(interactionContext)
                : DialogueContextFrame.empty(playerId);
        List<DialogueClaim> contextClaims = claimEngine.collectLocalClaims(
                participants, frame.interactionHints(), frame.contextSnapshot());
        Optional<DialogueParticipantDescriptor> defaultOwner = resolveDefaultOwner(participants);
        DialogueArbitrationDecision contextDecision = arbitrationPolicy.decide(participants, contextClaims, defaultOwner);
        if (contextDecision.accepted() && contextDecision.claim() != null) {
            publishPartner(partnerFor(playerId, contextDecision.owner(), false, now));
            schedulePartnerExpiry(playerId, now);
            return;
        }
        if (partnerTracker.leaseAlive(now)) {
            DialogueParticipantDescriptor holder = partnerTracker.holder(now).orElse(null);
            publishPartner(partnerFor(playerId, holder, false, now));
            schedulePartnerExpiry(playerId, now);
            return;
        }
        DialogueArbitrationDecision fallback = arbitrationPolicy.decide(participants, List.of(), defaultOwner);
        publishPartner(fallback.accepted()
                ? partnerFor(playerId, fallback.owner(), true, now)
                : PresencePartnerPayload.empty(playerId, now));
        schedulePartnerExpiry(playerId, now);
    }

    /** Builds the marker payload; the lease fields only travel when the shown participant owns the lease. */
    private PresencePartnerPayload partnerFor(
            String playerId,
            DialogueParticipantDescriptor owner,
            boolean defaultOwner,
            long now
    ) {
        if (owner == null) {
            return PresencePartnerPayload.empty(playerId, now);
        }
        boolean showLease = !defaultOwner
                && partnerTracker.leaseAlive(now)
                && owner.participantId().equals(partnerTracker.holderParticipantId(now));
        return new PresencePartnerPayload(
                playerId,
                owner.moduleId(),
                owner.participantId(),
                owner.hasPartnerIcon() ? owner.icon() : "",
                defaultOwner,
                showLease ? partnerTracker.leaseUntilMillis(now) : 0L,
                showLease ? partnerTracker.leaseDurationMillis(now) : 0L,
                now
        );
    }

    private void publishPartner(PresencePartnerPayload payload) {
        if (payload == null || payload.playerId().isBlank()) {
            return;
        }
        PresencePartnerPayload previous = partnerPayloads.get(payload.playerId());
        if (payload.sameDisplay(previous)) {
            return;
        }
        partnerPayloads.put(payload.playerId(), payload);
        adapter.publishPartner(payload);
    }

    /** Drops a lease whose participant is gone, so the marker cannot point at an unregistered participant. */
    private void pruneLease(List<DialogueParticipantDescriptor> participants, long now) {
        partnerTracker.holder(now).ifPresent(holder -> {
            boolean registered = participants.stream()
                    .anyMatch(participant -> holder.participantId().equals(participant.participantId()));
            if (!registered) {
                partnerTracker.clear();
            }
        });
    }

    private synchronized void refreshPartners(long now) {
        HashSet<String> playerIds = new HashSet<>(partnerPayloads.keySet());
        PresenceInteractionContextPayload context = interactionContext;
        if (context != null && !context.playerId().isBlank()) {
            playerIds.add(context.playerId());
        }
        for (String playerId : playerIds) {
            refreshPartner(playerId, now);
        }
    }

    /**
     * Recomputes the marker after the player changed the default owner.
     *
     * <p>Nothing else in the marker depends on that setting, so a single refresh is enough: the arbitration reads
     * the setting on its own when the next turn arrives.</p>
     */
    private void refreshPartnersOnDefaultOwnerChange() {
        refreshPartners(System.currentTimeMillis());
    }

    /**
     * Who owns a turn that no claim and no lease selected, according to the player's setting.
     *
     * <p>Resolved on every decision instead of cached: the setting can change while a world is open, and a cached
     * answer would keep sending turns to the module the player just replaced.</p>
     */
    private Optional<DialogueParticipantDescriptor> resolveDefaultOwner(List<DialogueParticipantDescriptor> participants) {
        return defaultOwnerResolver.resolve(participants, defaultOwnerSettings);
    }

    private void schedulePartnerExpiry(String playerId, long now) {
        cancelPartnerExpiry();
        if (runtimeContext == null || !partnerTracker.leaseAlive(now)) {
            return;
        }
        long delay = Math.max(1L, partnerTracker.leaseUntilMillis(now) - now);
        long generation = ++partnerExpiryGeneration;
        partnerExpiryHandle = runtime.schedule(
                ProtocolTaskSpec.builder()
                        .moduleId(moduleId())
                        .lane(ExecutionLane.SCHEDULED)
                        .taskId("ia.partner-lease-expiry")
                        .build(),
                () -> onPartnerLeaseExpired(generation, playerId),
                Duration.ofMillis(delay)
        );
    }

    private void cancelPartnerExpiry() {
        ProtocolTaskHandle handle = partnerExpiryHandle;
        partnerExpiryHandle = null;
        if (handle != null && !handle.isDone()) {
            handle.cancel("IA_PARTNER_LEASE_RENEWED");
        }
    }

    private void onPartnerLeaseExpired(long generation, String playerId) {
        if (generation != partnerExpiryGeneration || !runtimeAccepting) {
            return;
        }
        refreshPartner(playerId, System.currentTimeMillis());
    }

    private void handleSessionControl(TianshuEnvelope envelope, ProtocolContext context) {
        if (rejectWhenInactive(envelope, context)) {
            return;
        }
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
        if (payload.action() == DialogueSessionControlAction.EXTEND_PROCESSING) {
            Optional<DialogueParticipantDescriptor> owner = participantRegistry.find(session.ownerModuleId(), session.ownerParticipantId());
            if (owner.isEmpty()) {
                context.fail(envelope.envelopeId(), "SESSION_OWNER_NOT_FOUND", "Dialogue session owner is not registered", null);
                return;
            }
            if (!owner.get().turnProcessingPolicy().extendable()) {
                context.fail(envelope.envelopeId(), "SESSION_PROCESSING_EXTENSION_NOT_ALLOWED", "Dialogue session owner does not allow processing extension", null);
                return;
            }
            DialogueSession extended = sessionStore.extendProcessing(
                    session.sessionId(),
                    now,
                    owner.get().turnProcessingPolicy().extendDeadlineAt(session.createdAtMillis(), now, payload.requestedProcessingMillis())
            );
            eventPublisher.publish(envelope, extended, DialogueSessionEventType.CONVERSATION_CLAIMED, null, "PROCESSING_EXTENDED", now);
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

    private DialogueSession claimSession(DialogueArbitrationRequestPayload payload, DialogueParticipantDescriptor owner, long now) {
        return sessionStore.createClaimed(payload.playerId(), payload.turnId(), owner, now);
    }

    private boolean ownerChanged(Optional<DialogueSession> activeSession, DialogueParticipantDescriptor owner) {
        return activeSession.isPresent() && !activeSession.get().ownedBy(owner.moduleId(), owner.participantId());
    }

    private void releasePreviousTurnIfPresent(TianshuEnvelope envelope, Optional<DialogueSession> activeSession, DialogueSession currentSession, long now) {
        if (activeSession.isEmpty()) {
            return;
        }
        DialogueSession previous = activeSession.get();
        if (previous.sessionId().equals(currentSession.sessionId())) {
            return;
        }
        DialogueSession released = sessionStore.release(previous.sessionId(), DialogueReleaseReason.PREEMPTED, now);
        eventPublisher.publish(envelope, released, DialogueSessionEventType.CONVERSATION_RELEASED, DialogueReleaseReason.PREEMPTED, "TURN_SUPERSEDED", now);
        eventPublisher.publish(envelope, released, DialogueSessionEventType.CONVERSATION_SESSION_FINISHED, DialogueReleaseReason.PREEMPTED, "TURN_SUPERSEDED", now);
    }

    private DialogueArbitrationResultPayload acceptedResult(DialogueArbitrationRequestPayload request, DialogueSession session, DialogueParticipantDescriptor owner, String reason) {
        return new DialogueArbitrationResultPayload(request.requestId(), session.sessionId(), true, owner.moduleId(), owner.participantId(), owner.routeCapability(), reason, session.processingDeadlineMillis());
    }

    private DialogueArbitrationResultPayload rejectedResult(DialogueArbitrationRequestPayload request, DialogueSession session, String reason) {
        return new DialogueArbitrationResultPayload(request.requestId(), session.sessionId(), false, "", "", "", reason, session.processingDeadlineMillis());
    }

}
