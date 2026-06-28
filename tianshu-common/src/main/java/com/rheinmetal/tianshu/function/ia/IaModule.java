package com.rheinmetal.tianshu.function.ia;

import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.function.ia.claim.DialogueClaimEngine;
import com.rheinmetal.tianshu.function.ia.context.DialogueContextFrame;
import com.rheinmetal.tianshu.function.ia.context.DialogueContextSnapshot;
import com.rheinmetal.tianshu.function.ia.context.DialoguePresenceContextClient;
import com.rheinmetal.tianshu.function.ia.context.DialoguePresenceFactPlanner;
import com.rheinmetal.tianshu.function.ia.control.DialogueSessionControlDecision;
import com.rheinmetal.tianshu.function.ia.control.DialogueSessionControlPolicy;
import com.rheinmetal.tianshu.function.ia.diagnostics.DialogueDiagnosticsView;
import com.rheinmetal.tianshu.function.ia.event.DialogueArbitrationEventOrchestrator;
import com.rheinmetal.tianshu.function.ia.event.DialogueEventPublisher;
import com.rheinmetal.tianshu.function.ia.gateway.DialogueMessageGateway;
import com.rheinmetal.tianshu.function.ia.model.DialogueArbitrationInput;
import com.rheinmetal.tianshu.function.ia.model.DialogueArbitrationDecision;
import com.rheinmetal.tianshu.function.ia.model.DialogueAttentionState;
import com.rheinmetal.tianshu.function.ia.model.DialogueClaimCondition;
import com.rheinmetal.tianshu.function.ia.model.DialogueClaimConditionType;
import com.rheinmetal.tianshu.function.ia.model.DialogueClaimMode;
import com.rheinmetal.tianshu.function.ia.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.function.ia.model.DialogueReleaseReason;
import com.rheinmetal.tianshu.function.ia.model.DialogueSession;
import com.rheinmetal.tianshu.function.ia.model.DialogueSessionControlAction;
import com.rheinmetal.tianshu.function.ia.model.DialogueSessionEventType;
import com.rheinmetal.tianshu.function.ia.model.DialogueVoiceTriggerGroup;
import com.rheinmetal.tianshu.function.ia.payload.DialogueArbitrationRequestPayload;
import com.rheinmetal.tianshu.function.ia.payload.DialogueArbitrationResultPayload;
import com.rheinmetal.tianshu.function.ia.payload.DialogueLlmUsageAuthorizationRequestPayload;
import com.rheinmetal.tianshu.function.ia.payload.DialogueLlmUsageAuthorizationResultPayload;
import com.rheinmetal.tianshu.function.ia.payload.DialogueOwnerPreviewPayload;
import com.rheinmetal.tianshu.function.ia.payload.DialogueParticipantRegisterPayload;
import com.rheinmetal.tianshu.function.ia.payload.DialogueParticipantUnregisterPayload;
import com.rheinmetal.tianshu.function.ia.payload.DialogueSessionControlPayload;
import com.rheinmetal.tianshu.function.ia.policy.DialogueArbitrationPolicy;
import com.rheinmetal.tianshu.function.ia.registry.DialogueParticipantContractValidator;
import com.rheinmetal.tianshu.function.ia.registry.DialogueParticipantRegistry;
import com.rheinmetal.tianshu.function.ia.runtime.DialogueLifecycleSweeper;
import com.rheinmetal.tianshu.function.ia.runtime.DialogueParticipantLifecycleCoordinator;
import com.rheinmetal.tianshu.function.ia.security.DialogueAccessController;
import com.rheinmetal.tianshu.function.ia.security.DialogueAccessDecision;
import com.rheinmetal.tianshu.function.ia.security.DialogueLlmUsageAuthorizationPolicy;
import com.rheinmetal.tianshu.function.ia.session.DialogueAttentionMemory;
import com.rheinmetal.tianshu.function.ia.session.DialogueContextFreezeStore;
import com.rheinmetal.tianshu.function.ia.session.DialogueSessionStore;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.payload.AsrSpeechActivityPayload;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;
import com.rheinmetal.tianshu.protocol.registry.ValidationResult;
import com.rheinmetal.tianshu.protocol.voice.VoiceCommandCategory;
import com.rheinmetal.tianshu.protocol.voice.VoiceCommandScope;
import com.rheinmetal.tianshu.protocol.voice.VoiceResourceAccess;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerRegistration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class IaModule implements TianshuManagedModule {
    private static final Duration OWNER_PREVIEW_REFRESH_INTERVAL = Duration.ofMillis(500L);
    private static final Duration SPEECH_CONTEXT_RETENTION = Duration.ofSeconds(30L);

    private final ProtocolRuntime protocolRuntime;
    private final IaProtocolAdapter adapter;
    private final DialogueParticipantRegistry participantRegistry;
    private final DialogueSessionStore sessionStore;
    private final DialogueAttentionMemory attentionMemory;
    private final DialogueContextFreezeStore contextFreezeStore;
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
    private final DialogueParticipantContractValidator participantContractValidator;
    private final DialoguePresenceContextClient presenceContextClient;
    private final DialoguePresenceFactPlanner presenceFactPlanner;
    private final DialogueDiagnosticsView diagnosticsView;
    private final IaModuleService moduleService;
    private final Map<String, DialogueOwnerPreviewPayload> ownerPreviews = new ConcurrentHashMap<>();
    private final Set<String> voiceTriggerSyncedModules = ConcurrentHashMap.newKeySet();
    private volatile ProtocolTaskHandle ownerPreviewRefreshTask;
    private volatile boolean ownerPreviewRefreshActive;
    private ModuleRuntimeContext runtimeContext;

    public IaModule(ProtocolRuntime runtime) {
        this.protocolRuntime = runtime;
        this.adapter = new IaProtocolAdapter(runtime);
        this.participantRegistry = new DialogueParticipantRegistry();
        this.sessionStore = new DialogueSessionStore();
        this.attentionMemory = new DialogueAttentionMemory();
        this.contextFreezeStore = new DialogueContextFreezeStore(SPEECH_CONTEXT_RETENTION);
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
        this.participantContractValidator = new DialogueParticipantContractValidator(runtime.capabilities());
        this.presenceContextClient = new DialoguePresenceContextClient(adapter);
        this.presenceFactPlanner = new DialoguePresenceFactPlanner();
        this.diagnosticsView = new DialogueDiagnosticsView(participantRegistry, sessionStore);
        this.moduleService = new IaModuleService(participantRegistry, diagnosticsView, participantLifecycleCoordinator, participantContractValidator, this::handleParticipantsChanged);
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
        adapter.subscribeAsrSpeechActivity(this::handleAsrSpeechActivity);
        context.services().register(DialogueParticipantRegistry.class, participantRegistry);
        context.services().register(DialogueDiagnosticsView.class, diagnosticsView);
        context.services().register(IaModuleService.class, moduleService);
    }

    @Override
    public void prepare(ModuleRuntimeContext context) {
        runtimeContext = context;
        syncVoiceTriggersFromParticipants();
        context.runtimeState().capabilities().markReady(IaRuntimeCapabilities.ARBITRATION, moduleId());
        startOwnerPreviewRefresh();
    }

    @Override
    public void stop() {
        stopOwnerPreviewRefresh();
        long now = System.currentTimeMillis();
        clearSyncedVoiceTriggers();
        participantLifecycleCoordinator.unregisterModule(null, moduleId(), now);
    }

    @Override
    public void destroy() {
        stopOwnerPreviewRefresh();
        clearSyncedVoiceTriggers();
        participantRegistry.clear();
        sessionStore.clear();
        attentionMemory.clear();
        contextFreezeStore.clear();
        presenceContextClient.clear();
        ownerPreviews.clear();
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
        ValidationResult validation = participantContractValidator.validate(payload.descriptor());
        if (!validation.accepted()) {
            context.fail(envelope.envelopeId(), validation.code(), validation.message(), null);
            return;
        }
        participantRegistry.register(payload.descriptor());
        handleParticipantsChanged();
        refreshOwnerPreviews(envelope, System.currentTimeMillis());
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
        handleParticipantsChanged();
        refreshOwnerPreviews(envelope, now);
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
            respondArbitrationResultIfRequested(envelope, rejectedResult(payload, rejected, "REQUEST_EXPIRED"));
            context.complete(envelope.envelopeId());
            return;
        }
        List<DialogueParticipantDescriptor> participants = participantRegistry.snapshot();
        Optional<DialogueContextFrame> frozenContext = frozenContextFor(payload, now);
        if (frozenContext.isPresent()) {
            continueArbitration(envelope, context, payload, frozenContext.get(), now);
            return;
        }
        requestPresenceContext(
                envelope,
                "IA.arbitration." + payload.requestId(),
                Long.toString(payload.sourceSessionId()),
                payload.turnId(),
                payload.playerId(),
                payload.repairedText(),
                participants,
                frame -> continueArbitration(envelope, context, payload, frame, System.currentTimeMillis())
        );
    }

    private void continueArbitration(
            TianshuEnvelope envelope,
            ProtocolContext context,
            DialogueArbitrationRequestPayload payload,
            DialogueContextFrame contextFrame,
            long now
    ) {
        lifecycleSweeper.sweep(envelope, now);
        if (payload.expiredAt(now)) {
            DialogueSession rejected = sessionStore.reject(payload.playerId(), payload.turnId(), now);
            eventPublisher.publish(envelope, rejected, DialogueSessionEventType.CONVERSATION_REJECTED, DialogueReleaseReason.EXPIRED, "REQUEST_EXPIRED", now);
            respondArbitrationResultIfRequested(envelope, rejectedResult(payload, rejected, "REQUEST_EXPIRED"));
            context.complete(envelope.envelopeId());
            return;
        }
        List<DialogueParticipantDescriptor> participants = participantRegistry.snapshot();
        Optional<DialogueSession> activeSession = sessionStore.activeForPlayer(payload.playerId(), now);
        Optional<DialogueAttentionState> attentionState = attentionMemory.activeForPlayer(payload.playerId(), participants, now);
        DialogueArbitrationInput input = DialogueArbitrationInput.from(payload, withPlayerId(contextFrame, payload.playerId()));
        DialogueArbitrationDecision decision = arbitrationPolicy.decide(participants, claimEngine.collectLocalClaims(participants, input), attentionState);
        if (!decision.accepted()) {
            publishOwnerPreviewIfChanged(envelope, emptyPreview(payload.playerId(), now));
            DialogueSession rejected = sessionStore.reject(payload.playerId(), payload.turnId(), now);
            eventPublisher.publish(envelope, rejected, DialogueSessionEventType.CONVERSATION_REJECTED, DialogueReleaseReason.REJECTED, decision.reason(), now);
            respondArbitrationResultIfRequested(envelope, rejectedResult(payload, rejected, decision.reason()));
            context.complete(envelope.envelopeId());
            return;
        }
        rememberAttentionForDecision(payload.playerId(), decision, now);
        publishOwnerPreviewIfChanged(envelope, previewFor(payload.playerId(), decision.owner(), now));
        DialogueSession session = claimSession(payload, decision.owner(), now);
        releasePreviousTurnIfPresent(envelope, activeSession, session, now);
        arbitrationEventOrchestrator.publishAccepted(envelope, session, ownerChanged(activeSession, decision.owner()), decision.reason(), now);
        DialogueAccessDecision deliveryDecision = messageGateway.deliverToOwner(envelope, session, decision.owner(), input);
        if (!deliveryDecision.allowed()) {
            DialogueSession released = sessionStore.release(session.sessionId(), DialogueReleaseReason.ACCESS_DENIED, now);
            eventPublisher.publish(envelope, released, DialogueSessionEventType.CONVERSATION_RELEASED, DialogueReleaseReason.ACCESS_DENIED, deliveryDecision.reasonCode(), now);
            context.fail(envelope.envelopeId(), deliveryDecision.reasonCode(), deliveryDecision.message(), null);
            return;
        }
        DialogueSession active = sessionStore.activate(session.sessionId(), now);
        respondArbitrationResultIfRequested(envelope, acceptedResult(payload, active, decision.owner(), decision.reason()));
        context.complete(envelope.envelopeId());
    }

    private void handleAsrSpeechActivity(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof AsrSpeechActivityPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "ASR speech activity payload is invalid", null);
            return;
        }
        long now = payload.occurredAtMillis() > 0L ? payload.occurredAtMillis() : System.currentTimeMillis();
        if (payload.speaking()) {
            List<DialogueParticipantDescriptor> participants = participantRegistry.snapshot();
            requestPresenceContext(
                    envelope,
                    "IA.speech." + payload.sessionId(),
                    Long.toString(payload.sessionId()),
                    "",
                    "",
                    "",
                    participants,
                    frame -> contextFreezeStore.freeze(payload.sessionId(), frame, now)
            );
        } else {
            contextFreezeStore.markEnded(payload.sessionId(), now);
        }
        context.complete(envelope.envelopeId());
    }

    private Optional<DialogueContextFrame> frozenContextFor(DialogueArbitrationRequestPayload payload, long now) {
        contextFreezeStore.sweep(now);
        return contextFreezeStore.consume(payload.sourceSessionId(), now)
                .map(frame -> withPlayerId(frame, payload.playerId()));
    }

    private void handleParticipantsChanged() {
        syncVoiceTriggersFromParticipants();
    }

    private void requestPresenceContext(
            TianshuEnvelope parent,
            String requestId,
            String sessionId,
            String turnId,
            String playerId,
            String userText,
            List<DialogueParticipantDescriptor> participants,
            DialoguePresenceContextClient.Completion completion
    ) {
        presenceContextClient.request(
                parent,
                requestId,
                sessionId,
                turnId,
                playerId,
                userText,
                presenceFactPlanner.plan(participants),
                completion
        );
    }

    private void syncVoiceTriggersFromParticipants() {
        VoiceResourceAccess resources = runtimeContext == null ? null : runtimeContext.voiceResources();
        if (resources == null || resources.voiceTriggers() == null) {
            return;
        }
        Map<String, VoiceTriggerWords> desiredTriggers = voiceTriggersByModule(participantRegistry.snapshot());
        for (String syncedModule : Set.copyOf(voiceTriggerSyncedModules)) {
            if (!desiredTriggers.containsKey(syncedModule)) {
                resources.voiceTriggers().unregisterModule(syncedModule);
                voiceTriggerSyncedModules.remove(syncedModule);
            }
        }
        for (Map.Entry<String, VoiceTriggerWords> entry : desiredTriggers.entrySet()) {
            VoiceTriggerWords words = entry.getValue();
            resources.voiceTriggers().register(new VoiceTriggerRegistration(
                    entry.getKey(),
                    words.wakeWords(),
                    words.extraWords(),
                    VoiceCommandCategory.GENERAL,
                    words.priority(),
                    VoiceCommandScope.CLIENT,
                    true
            ));
            voiceTriggerSyncedModules.add(entry.getKey());
        }
    }

    private void clearSyncedVoiceTriggers() {
        VoiceResourceAccess resources = runtimeContext == null ? null : runtimeContext.voiceResources();
        if (resources == null || resources.voiceTriggers() == null) {
            voiceTriggerSyncedModules.clear();
            return;
        }
        for (String moduleId : Set.copyOf(voiceTriggerSyncedModules)) {
            resources.voiceTriggers().unregisterModule(moduleId);
        }
        voiceTriggerSyncedModules.clear();
    }

    private List<String> extractWakeWords(DialogueParticipantDescriptor descriptor) {
        if (descriptor.claimProfile() == null || descriptor.claimProfile().mode() != DialogueClaimMode.RULES) {
            return List.of();
        }
        List<String> words = new ArrayList<>();
        descriptor.claimProfile().rules().forEach(rule -> {
            if (rule == null || rule.conditions().isEmpty()) {
                return;
            }
            for (DialogueClaimCondition condition : rule.conditions()) {
                if (condition != null && condition.type() == DialogueClaimConditionType.WAKE_WORD) {
                    words.addAll(condition.values());
                }
            }
        });
        return words.stream()
                .filter(word -> word != null && !word.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private Map<String, VoiceTriggerWords> voiceTriggersByModule(List<DialogueParticipantDescriptor> participants) {
        if (participants == null || participants.isEmpty()) {
            return Map.of();
        }
        Map<String, VoiceTriggerAccumulator> accumulators = new LinkedHashMap<>();
        for (DialogueParticipantDescriptor participant : participants) {
            if (participant == null || participant.moduleId().isBlank()) {
                continue;
            }
            VoiceTriggerWords words = triggerWordsFor(participant);
            if (words.empty()) {
                continue;
            }
            accumulators.computeIfAbsent(participant.moduleId(), ignored -> new VoiceTriggerAccumulator())
                    .add(words, participant.priority());
        }
        Map<String, VoiceTriggerWords> result = new LinkedHashMap<>();
        for (Map.Entry<String, VoiceTriggerAccumulator> entry : accumulators.entrySet()) {
            result.put(entry.getKey(), entry.getValue().toWords());
        }
        return result;
    }

    private VoiceTriggerWords triggerWordsFor(DialogueParticipantDescriptor participant) {
        List<String> claimWakeWords = extractWakeWords(participant);
        DialogueVoiceTriggerGroup group = participant.voiceTriggerGroup();
        LinkedHashSet<String> wakeWords = new LinkedHashSet<>(claimWakeWords);
        LinkedHashSet<String> extraWords = new LinkedHashSet<>();
        if (group != null) {
            wakeWords.addAll(group.wakeWords());
            extraWords.addAll(group.extraWords());
        }
        return new VoiceTriggerWords(List.copyOf(wakeWords), List.copyOf(extraWords), participant.priority());
    }

    private static final class VoiceTriggerAccumulator {
        private final LinkedHashSet<String> wakeWords = new LinkedHashSet<>();
        private final LinkedHashSet<String> extraWords = new LinkedHashSet<>();
        private int priority;

        private void add(VoiceTriggerWords words, int participantPriority) {
            wakeWords.addAll(words.wakeWords());
            extraWords.addAll(words.extraWords());
            priority = Math.max(priority, participantPriority);
        }

        private VoiceTriggerWords toWords() {
            return new VoiceTriggerWords(List.copyOf(wakeWords), List.copyOf(extraWords), priority);
        }
    }

    private record VoiceTriggerWords(List<String> wakeWords, List<String> extraWords, int priority) {
        private boolean empty() {
            return wakeWords.isEmpty() && extraWords.isEmpty();
        }
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
                )
        );
    }

    private void respondArbitrationResultIfRequested(TianshuEnvelope envelope, DialogueArbitrationResultPayload result) {
        if (envelope.header().packetType() == PacketType.REQUEST) {
            adapter.respondArbitrationResult(envelope, result);
        }
    }

    private void rememberAttentionForDecision(String playerId, DialogueArbitrationDecision decision, long now) {
        if (decision.claim() != null) {
            attentionMemory.remember(playerId, decision.owner(), decision.claim(), now);
        } else if ("DEFAULT_OWNER".equals(decision.reason()) || !decision.accepted()) {
            attentionMemory.clearPlayer(playerId);
        }
    }

    private void publishOwnerPreviewIfChanged(TianshuEnvelope parent, DialogueOwnerPreviewPayload preview) {
        if (preview == null || preview.playerId().isBlank()) {
            return;
        }
        DialogueOwnerPreviewPayload previous = ownerPreviews.get(preview.playerId());
        if (preview.sameOwner(previous)) {
            return;
        }
        ownerPreviews.put(preview.playerId(), preview);
        adapter.publishOwnerPreview(parent, preview);
    }

    private DialogueOwnerPreviewPayload previewFor(String playerId, DialogueParticipantDescriptor owner, long now) {
        if (owner == null) {
            return emptyPreview(playerId, now);
        }
        return new DialogueOwnerPreviewPayload(playerId, owner.moduleId(), owner.participantId(), owner.displayName(), now);
    }

    private DialogueOwnerPreviewPayload emptyPreview(String playerId, long now) {
        return new DialogueOwnerPreviewPayload(playerId, "", "", "", now);
    }

    private void refreshOwnerPreviews(TianshuEnvelope parent, long now) {
        List<DialogueParticipantDescriptor> participants = participantRegistry.snapshot();
        Set<String> playerIds = new HashSet<>(ownerPreviews.keySet());
        playerIds.addAll(attentionMemory.playerIds());
        for (String playerId : playerIds) {
            Optional<DialogueAttentionState> attentionState = attentionMemory.activeForPlayer(playerId, participants, now);
            DialogueArbitrationDecision decision = arbitrationPolicy.decide(participants, List.of(), attentionState);
            if (decision.accepted()) {
                rememberAttentionForDecision(playerId, decision, now);
                publishOwnerPreviewIfChanged(parent, previewFor(playerId, decision.owner(), now));
            } else {
                attentionMemory.clearPlayer(playerId);
                publishOwnerPreviewIfChanged(parent, emptyPreview(playerId, now));
            }
        }
    }

    private void startOwnerPreviewRefresh() {
        if (ownerPreviewRefreshActive) {
            return;
        }
        ownerPreviewRefreshActive = true;
        scheduleOwnerPreviewRefresh();
    }

    private void stopOwnerPreviewRefresh() {
        ownerPreviewRefreshActive = false;
        ProtocolTaskHandle task = ownerPreviewRefreshTask;
        if (task != null && !task.isDone()) {
            task.cancel("IA stopped");
        }
        ownerPreviewRefreshTask = null;
    }

    private void scheduleOwnerPreviewRefresh() {
        if (!ownerPreviewRefreshActive) {
            return;
        }
        ownerPreviewRefreshTask = protocolRuntime.executors().schedule(
                ProtocolTaskSpec.builder()
                        .moduleId(moduleId())
                        .lane(ExecutionLane.SCHEDULED)
                        .taskId("ia.owner-preview-refresh")
                        .build(),
                () -> {
                    if (!ownerPreviewRefreshActive) {
                        return;
                    }
                    long now = System.currentTimeMillis();
                    contextFreezeStore.sweep(now);
                    refreshOwnerPreviews(null, now);
                    scheduleOwnerPreviewRefresh();
                },
                OWNER_PREVIEW_REFRESH_INTERVAL
        );
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
            DialogueSession extended = sessionStore.extendProcessing(session.sessionId(), now, owner.get().turnProcessingPolicy().extendDeadlineAt(now, payload.requestedProcessingMillis()));
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
                session == null ? 0L : session.processingDeadlineMillis()
        );
    }
}
