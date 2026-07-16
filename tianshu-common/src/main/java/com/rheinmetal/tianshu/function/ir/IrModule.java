package com.rheinmetal.tianshu.function.ir;

import com.rheinmetal.tianshu.api.diagnostics.DiagnosticEvent;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticPrivacy;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSeverity;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.AsrTextPayload;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;
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
    private final ConcurrentMap<String, PendingPresenceContext> pendingPresenceContexts = new ConcurrentHashMap<>();
    private final Object indexLock = new Object();
    private volatile List<VoiceTriggerRegistration> indexedRegistrations = List.of();
    private volatile List<IrCompiledVoiceTrigger> voiceTriggerIndex = List.of();
    private ModuleProtocolAccess protocol;
    private VoiceResourceAccess voiceResources;
    private volatile boolean acceptingInput = true;
    private com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink diagnostics = com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink.NOOP;

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
    }

    @Override
    public String moduleId() {
        return IrProtocolAdapter.MODULE_ID;
    }

    @Override
    public void register(ModuleRegistrationContext context) {
        protocol = context.protocol();
        adapter.subscribeAsrFinalText(this::handleAsrFinalText);
    }

    @Override
    public void prepare(ModuleRuntimeContext context) {
        acceptingInput = true;
        diagnostics = context == null ? com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink.NOOP : context.diagnostics();
        voiceResources = context == null ? null : context.voiceResources();
        refreshVoiceTriggerIndex(currentVoiceTriggerRegistrations());
    }

    @Override
    public void stop() {
        acceptingInput = false;
        clearPendingPresenceContexts();
    }

    @Override
    public void destroy() {
        stop();
        voiceResources = null;
        indexedRegistrations = List.of();
        voiceTriggerIndex = List.of();
        diagnostics = com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink.NOOP;
    }

    private void handleAsrFinalText(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof AsrTextPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "ASR payload is invalid", null);
            return;
        }
        if (!acceptingInput) {
            return;
        }
        processInput(envelope, IrInputMapper.fromAsr(payload));
    }

    private void processInput(TianshuEnvelope envelope, IrInputText input) {
        if (input == null || input.blank()) {
            publishDiagnostic("EMPTY_INPUT", DiagnosticSeverity.DEBUG, Map.of(
                    "rawText", input == null ? "" : input.rawText()
            ));
            return;
        }
        IrPreparedInput prepared = preprocessor.prepare(input);
        requestPresenceContext(envelope, input, hint -> continueProcessing(envelope, input, prepared, hint));
    }

    private void continueProcessing(
            TianshuEnvelope envelope,
            IrInputText input,
            IrPreparedInput prepared,
            IrContextHint contextHint
    ) {
        if (!acceptingInput) {
            return;
        }
        IrNamedObjectEnhancementResult namedObjectEnhancement = namedObjectEnhancer.enhance(prepared, contextHint);
        List<IrCompiledVoiceTrigger> index = ensureVoiceTriggerIndex();
        IrInputText voiceInput = wakeWordEnhancer.enhance(inputWithNamedObjectRepair(prepared, namedObjectEnhancement), index);
        IrMatchBatch batch = matcher.match(voiceInput, index);
        adapter.publishResult(envelope, new IrResultPayload(
                voiceInput.text(),
                prepared.filteredText(),
                batch.matches(),
                namedObjectEnhancement.matchedItemIds(),
                namedObjectEnhancement.matchedEntityTypeIds(),
                voiceInput.turnId(),
                voiceInput.sessionId(),
                System.currentTimeMillis()
        ));
        publishDiagnostic("ANALYSIS_PUBLISHED", DiagnosticSeverity.INFO, Map.of(
                "rawText", input.rawText(),
                "repairedText", voiceInput.text(),
                "voiceMatchCount", Integer.toString(batch.matches().size()),
                "itemMatchCount", Integer.toString(namedObjectEnhancement.matchedItemIds().size()),
                "entityMatchCount", Integer.toString(namedObjectEnhancement.matchedEntityTypeIds().size())
        ));
    }

    private void requestPresenceContext(
            TianshuEnvelope parent,
            IrInputText input,
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
        PendingPresenceContext pending = new PendingPresenceContext(completion);
        pendingPresenceContexts.put(queryEnvelope.envelopeId(), pending);
        adapter.registerPresenceContextSnapshotResponse(queryEnvelope.envelopeId(), this::handlePresenceContextResponse);
        adapter.submitPresenceContextQuery(queryEnvelope);
        schedulePresenceContextTimeout(queryEnvelope.envelopeId());
    }

    private void handlePresenceContextResponse(TianshuEnvelope envelope, ProtocolContext context) {
        String requestEnvelopeId = envelope == null ? "" : envelope.parentId();
        PendingPresenceContext pending = pendingPresenceContexts.remove(requestEnvelopeId);
        if (pending == null) {
            context.complete(envelope.envelopeId());
            return;
        }
        adapter.unregisterPresenceContextResponses(requestEnvelopeId);
        if (!acceptingInput) {
            context.complete(envelope.envelopeId());
            return;
        }
        IrContextHint hint = IrContextHint.empty();
        if (envelope.payload() instanceof PresenceContextSnapshotPayload payload && payload.success()) {
            hint = IrPresenceContextMapper.toHint(payload);
        }
        pending.completion().complete(hint);
        context.complete(envelope.envelopeId());
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
        if (!acceptingInput) {
            return;
        }
        pending.completion().complete(IrContextHint.empty());
    }

    private void clearPendingPresenceContexts() {
        for (String requestEnvelopeId : pendingPresenceContexts.keySet()) {
            pendingPresenceContexts.remove(requestEnvelopeId);
            adapter.unregisterPresenceContextResponses(requestEnvelopeId);
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

    private void publishDiagnostic(String code, DiagnosticSeverity severity, Map<String, String> attributes) {
        diagnostics.publish(DiagnosticEvent.now(IrProtocolAdapter.MODULE_ID, code, severity, DiagnosticPrivacy.RAW_CONTENT, attributes));
    }

    private interface PresenceContextCompletion {
        void complete(IrContextHint hint);
    }

    private record PendingPresenceContext(PresenceContextCompletion completion) {
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
