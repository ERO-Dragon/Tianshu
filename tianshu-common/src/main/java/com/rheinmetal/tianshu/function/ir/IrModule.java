package com.rheinmetal.tianshu.function.ir;

import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.AsrTextPayload;
import com.rheinmetal.tianshu.protocol.payload.IrParsePayload;
import com.rheinmetal.tianshu.protocol.payload.IrResultPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextQueryPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextSnapshotPayload;
import com.rheinmetal.tianshu.protocol.runtime.ModuleProtocolAccess;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;
import com.rheinmetal.tianshu.protocol.voice.VoiceResourceAccess;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerRegistration;
import com.rheinmetal.tianshu.protocol.PresenceContextFactIds;
import com.rheinmetal.tianshu.function.ir.enhance.IrNamedObjectEnhancementResult;
import com.rheinmetal.tianshu.function.ir.enhance.IrNamedObjectEnhancer;
import com.rheinmetal.tianshu.function.ir.enhance.IrContextHint;
import com.rheinmetal.tianshu.function.ir.input.IrInputMapper;
import com.rheinmetal.tianshu.function.ir.input.IrInputPreprocessor;
import com.rheinmetal.tianshu.function.ir.input.IrInputText;
import com.rheinmetal.tianshu.function.ir.input.IrPreparedInput;
import com.rheinmetal.tianshu.function.ir.routing.IrRouteKind;
import com.rheinmetal.tianshu.function.ir.routing.IrRoutingDecision;
import com.rheinmetal.tianshu.function.ir.routing.IrRoutingPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

public final class IrModule implements TianshuManagedModule {
    private static final long PRESENCE_CONTEXT_TIMEOUT_MILLIS = 300L;

    private final IrProtocolAdapter adapter;
    private final IrInputPreprocessor preprocessor;
    private final IrNamedObjectEnhancer namedObjectEnhancer;
    private final IrWakeWordEnhancer wakeWordEnhancer;
    private final IrVoiceTriggerIndexer indexer;
    private final IrVoiceTriggerMatcher matcher;
    private final IrRoutingPolicy routingPolicy;
    private final IrDialogueArbitrationRequestMapper dialogueMapper;
    private final ConcurrentMap<String, PendingPresenceContext> pendingPresenceContexts = new ConcurrentHashMap<>();
    private final Object indexLock = new Object();
    private volatile List<VoiceTriggerRegistration> indexedRegistrations = List.of();
    private volatile List<IrCompiledVoiceTrigger> voiceTriggerIndex = List.of();
    private ModuleProtocolAccess protocol;
    private VoiceResourceAccess voiceResources;

    public IrModule(ModuleRuntimeAccess runtime) {
        this(runtime, IrNamedObjectEnhancer.noop());
    }

    public IrModule(ModuleRuntimeAccess runtime, IrNamedObjectEnhancer namedObjectEnhancer) {
        this.adapter = new IrProtocolAdapter(runtime);
        this.preprocessor = new IrInputPreprocessor();
        this.namedObjectEnhancer = namedObjectEnhancer == null ? IrNamedObjectEnhancer.noop() : namedObjectEnhancer;
        this.wakeWordEnhancer = new IrWakeWordEnhancer();
        this.indexer = new IrVoiceTriggerIndexer();
        this.matcher = new IrVoiceTriggerMatcher();
        this.routingPolicy = new IrRoutingPolicy();
        this.dialogueMapper = new IrDialogueArbitrationRequestMapper();
    }

    @Override
    public String moduleId() {
        return IrProtocolAdapter.MODULE_ID;
    }

    @Override
    public void register(ModuleRegistrationContext context) {
        protocol = context.protocol();
        adapter.subscribeAsrFinalText(this::handleAsrFinalText);
        adapter.registerParseCapability(this::handleParseRequest);
    }

    @Override
    public void prepare(ModuleRuntimeContext context) {
        voiceResources = context == null ? null : context.voiceResources();
        refreshVoiceTriggerIndex(currentVoiceTriggerRegistrations());
    }

    @Override
    public void destroy() {
        voiceResources = null;
        indexedRegistrations = List.of();
        voiceTriggerIndex = List.of();
        clearPendingPresenceContexts();
    }

    private void handleAsrFinalText(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof AsrTextPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "ASR payload is invalid", null);
            return;
        }
        processInput(envelope, context, IrInputMapper.fromAsr(payload), false);
    }

    private void handleParseRequest(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof IrParsePayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "IR payload is invalid", null);
            return;
        }
        processInput(envelope, context, IrInputMapper.fromParse(payload), true);
    }

    private void processInput(TianshuEnvelope envelope, ProtocolContext context, IrInputText input, boolean completeSourceEnvelope) {
        if (input == null || input.blank()) {
            publishNoMatch(envelope, input, "EMPTY_INPUT");
            completeIfNeeded(context, envelope, completeSourceEnvelope);
            return;
        }
        IrPreparedInput prepared = preprocessor.prepare(input);
        requestPresenceContext(envelope, context, input, completeSourceEnvelope, hint -> continueProcessing(envelope, context, input, prepared, hint, completeSourceEnvelope));
    }

    private void continueProcessing(
            TianshuEnvelope envelope,
            ProtocolContext context,
            IrInputText input,
            IrPreparedInput prepared,
            IrContextHint contextHint,
            boolean completeSourceEnvelope
    ) {
        IrNamedObjectEnhancementResult namedObjectEnhancement = namedObjectEnhancer.enhance(prepared, contextHint);
        List<IrCompiledVoiceTrigger> index = ensureVoiceTriggerIndex();
        IrInputText voiceInput = wakeWordEnhancer.enhance(inputWithNamedObjectRepair(prepared, namedObjectEnhancement), index);
        IrMatchBatch batch = matcher.match(voiceInput, index);
        IrRoutingDecision decision = routingPolicy.decide(voiceInput, batch);
        if (decision.kind() == IrRouteKind.NO_MATCH) {
            publishNoMatch(envelope, voiceInput, decision.reason());
            completeIfNeeded(context, envelope, completeSourceEnvelope);
            return;
        }
        submitDialogueArbitration(envelope, voiceInput, prepared, namedObjectEnhancement, batch);
        publishDialogueRouted(envelope, voiceInput, batch);
        completeIfNeeded(context, envelope, completeSourceEnvelope);
    }

    private void requestPresenceContext(
            TianshuEnvelope parent,
            ProtocolContext sourceContext,
            IrInputText input,
            boolean completeSourceEnvelope,
            PresenceContextCompletion completion
    ) {
        if (adapter.presenceContextProviderCount() <= 0) {
            completion.complete(IrContextHint.empty());
            return;
        }
        PresenceContextQueryPayload payload = new PresenceContextQueryPayload(
                "IR.context." + input.turnId(),
                Long.toString(input.sessionId()),
                Integer.toString(input.turnId()),
                "",
                "",
                "",
                input.text(),
                List.of(),
                System.currentTimeMillis(),
                List.of(PresenceContextFactIds.INTERACTION_CONTEXT, PresenceContextFactIds.PLAYER_INVENTORY)
        );
        TianshuEnvelope queryEnvelope = adapter.buildPresenceContextQuery(parent, payload);
        PendingPresenceContext pending = new PendingPresenceContext(sourceContext, parent, completeSourceEnvelope, completion);
        pendingPresenceContexts.put(queryEnvelope.envelopeId(), pending);
        adapter.registerPresenceContextSnapshotResponse(queryEnvelope.envelopeId(), this::handlePresenceContextResponse);
        adapter.submitPresenceContextQuery(queryEnvelope);
        schedulePresenceContextTimeout(queryEnvelope.envelopeId());
    }

    private void handlePresenceContextResponse(TianshuEnvelope envelope, ProtocolContext context) {
        String requestEnvelopeId = envelope == null ? "" : envelope.parentId();
        PendingPresenceContext pending = pendingPresenceContexts.remove(requestEnvelopeId);
        if (pending == null) {
            completeIfNeeded(context, envelope, true);
            return;
        }
        adapter.unregisterPresenceContextResponses(requestEnvelopeId);
        IrContextHint hint = IrContextHint.empty();
        if (envelope.payload() instanceof PresenceContextSnapshotPayload payload && payload.success()) {
            hint = IrPresenceContextMapper.toHint(payload);
        }
        pending.completion().complete(hint);
        completeIfNeeded(context, envelope, true);
    }

    private void schedulePresenceContextTimeout(String requestEnvelopeId) {
        CompletableFuture.delayedExecutor(PRESENCE_CONTEXT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .execute(() -> timeoutPresenceContext(requestEnvelopeId));
    }

    private void timeoutPresenceContext(String requestEnvelopeId) {
        PendingPresenceContext pending = pendingPresenceContexts.remove(requestEnvelopeId);
        if (pending == null) {
            return;
        }
        adapter.unregisterPresenceContextResponses(requestEnvelopeId);
        pending.completion().complete(IrContextHint.empty());
        completeIfNeeded(pending.sourceContext(), pending.sourceEnvelope(), pending.completeSourceEnvelope());
    }

    private void clearPendingPresenceContexts() {
        for (String requestEnvelopeId : pendingPresenceContexts.keySet()) {
            PendingPresenceContext pending = pendingPresenceContexts.remove(requestEnvelopeId);
            adapter.unregisterPresenceContextResponses(requestEnvelopeId);
            if (pending != null) {
                pending.completion().complete(IrContextHint.empty());
                completeIfNeeded(pending.sourceContext(), pending.sourceEnvelope(), pending.completeSourceEnvelope());
            }
        }
    }

    private IrInputText inputWithNamedObjectRepair(IrPreparedInput prepared, IrNamedObjectEnhancementResult namedObjectEnhancement) {
        IrInputText voiceInput = prepared == null ? null : prepared.voiceInput();
        if (voiceInput == null) {
            return new IrInputText("", "", 0, 0L, "", System.currentTimeMillis());
        }
        String repaired = namedObjectEnhancement == null ? "" : namedObjectEnhancement.repairedText();
        if (repaired == null || repaired.isBlank()) {
            return voiceInput;
        }
        return new IrInputText(repaired, voiceInput.rawText(), voiceInput.turnId(), voiceInput.sessionId(), voiceInput.source(), voiceInput.createdAt());
    }

    private List<IrCompiledVoiceTrigger> ensureVoiceTriggerIndex() {
        List<VoiceTriggerRegistration> registrations = currentVoiceTriggerRegistrations();
        if (registrations.equals(indexedRegistrations)) {
            return voiceTriggerIndex;
        }
        synchronized (indexLock) {
            if (!registrations.equals(indexedRegistrations)) {
                refreshVoiceTriggerIndex(registrations);
            }
            return voiceTriggerIndex;
        }
    }

    private void refreshVoiceTriggerIndex(List<VoiceTriggerRegistration> registrations) {
        indexedRegistrations = registrations == null || registrations.isEmpty() ? List.of() : List.copyOf(registrations);
        voiceTriggerIndex = indexer.compile(indexedRegistrations);
    }

    private List<VoiceTriggerRegistration> currentVoiceTriggerRegistrations() {
        VoiceResourceAccess resources = voiceResources;
        if (resources != null && resources.voiceTriggers() != null) {
            return resources.voiceTriggers().registrations();
        }
        return protocol == null ? List.of() : protocol.voiceTriggers().registrations();
    }

    private void submitDialogueArbitration(TianshuEnvelope envelope, IrInputText voiceInput, IrPreparedInput prepared,
                                           IrNamedObjectEnhancementResult namedObjectEnhancement, IrMatchBatch batch) {
        adapter.commandDialogueArbitration(envelope, dialogueMapper.map(
                voiceInput,
                prepared,
                namedObjectEnhancement,
                batch
        ));
    }

    private void publishDialogueRouted(TianshuEnvelope envelope, IrInputText input, IrMatchBatch batch) {
        adapter.publishResult(envelope, new IrResultPayload(
                true,
                "",
                "DIALOGUE_ARBITRATION",
                targetSummary(batch == null ? List.of() : batch.matches()),
                maxConfidence(batch == null ? List.of() : batch.matches()),
                true,
                "DIALOGUE_ROUTED",
                input.turnId(),
                input.sessionId()
        ));
    }

    private void publishNoMatch(TianshuEnvelope envelope, IrInputText input, String reason) {
        String text = input == null ? "" : input.text();
        int turnId = input == null ? 0 : input.turnId();
        long sessionId = input == null ? 0L : input.sessionId();
        adapter.publishResult(envelope, new IrResultPayload(
                false,
                text,
                "DIALOGUE_ARBITRATION",
                "",
                0.0D,
                false,
                reason,
                turnId,
                sessionId
        ));
    }

    private String targetSummary(List<IrVoiceMatch> matches) {
        if (matches == null || matches.isEmpty()) {
            return "";
        }
        return matches.stream()
                .map(IrVoiceMatch::moduleId)
                .filter(moduleId -> moduleId != null && !moduleId.isBlank())
                .distinct()
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    private double maxConfidence(List<IrVoiceMatch> matches) {
        if (matches == null || matches.isEmpty()) {
            return 0.0D;
        }
        return matches.stream()
                .mapToDouble(IrVoiceMatch::confidence)
                .max()
                .orElse(0.0D);
    }

    private void completeIfNeeded(ProtocolContext context, TianshuEnvelope envelope, boolean enabled) {
        if (!enabled || context == null || envelope == null) {
            return;
        }
        context.complete(envelope.envelopeId());
    }

    private interface PresenceContextCompletion {
        void complete(IrContextHint hint);
    }

    private record PendingPresenceContext(
            ProtocolContext sourceContext,
            TianshuEnvelope sourceEnvelope,
            boolean completeSourceEnvelope,
            PresenceContextCompletion completion
    ) {
    }

    private static final class IrPresenceContextMapper {
        private static IrContextHint toHint(PresenceContextSnapshotPayload payload) {
            if (payload == null || payload.facts().isEmpty()) {
                return IrContextHint.empty();
            }
            List<String> itemIds = new ArrayList<>();
            for (PresenceContextSnapshotPayload.FactPayload fact : payload.facts()) {
                if (fact == null || fact.nativeValues().isEmpty()) {
                    continue;
                }
                addItemId(itemIds, fact.nativeValues().get("heldItemId"));
                addDelimitedValues(itemIds, fact.nativeValues().get("equippedItemIds"));
                addInventoryItems(itemIds, fact.nativeValues().get("items"));
            }
            return new IrContextHint(itemIds);
        }

        private static void addDelimitedValues(List<String> values, String raw) {
            if (raw == null || raw.isBlank()) {
                return;
            }
            for (String value : raw.split("\\|")) {
                addItemId(values, value);
            }
        }

        private static void addInventoryItems(List<String> values, String raw) {
            if (raw == null || raw.isBlank()) {
                return;
            }
            for (String entry : raw.split("\\|")) {
                int separator = entry.lastIndexOf(':');
                addItemId(values, separator > 0 ? entry.substring(0, separator) : entry);
            }
        }

        private static void addItemId(List<String> values, String value) {
            Optional.ofNullable(value)
                    .map(String::trim)
                    .filter(itemId -> !itemId.isBlank())
                    .ifPresent(values::add);
        }
    }

}
