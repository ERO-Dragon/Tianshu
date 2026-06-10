package com.rheinmetal.tianshu.function.ir;

import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.AsrTextPayload;
import com.rheinmetal.tianshu.protocol.payload.IrParsePayload;
import com.rheinmetal.tianshu.protocol.payload.IrResultPayload;
import com.rheinmetal.tianshu.protocol.runtime.ModuleProtocolAccess;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerRegistration;
import com.rheinmetal.tianshu.function.ir.enhance.IrNamedObjectEnhancementResult;
import com.rheinmetal.tianshu.function.ir.enhance.IrNamedObjectEnhancer;
import com.rheinmetal.tianshu.function.ir.input.IrInputMapper;
import com.rheinmetal.tianshu.function.ir.input.IrInputPreprocessor;
import com.rheinmetal.tianshu.function.ir.input.IrInputText;
import com.rheinmetal.tianshu.function.ir.input.IrPreparedInput;
import com.rheinmetal.tianshu.function.ir.routing.IrRouteKind;
import com.rheinmetal.tianshu.function.ir.routing.IrRoutingDecision;
import com.rheinmetal.tianshu.function.ir.routing.IrRoutingPolicy;

import java.util.List;

public final class IrModule implements TianshuManagedModule {
    private final IrProtocolAdapter adapter;
    private final IrInputPreprocessor preprocessor;
    private final IrNamedObjectEnhancer namedObjectEnhancer;
    private final IrWakeWordEnhancer wakeWordEnhancer;
    private final IrVoiceTriggerIndexer indexer;
    private final IrVoiceTriggerMatcher matcher;
    private final IrRoutingPolicy routingPolicy;
    private final IrDialogueArbitrationRequestMapper dialogueMapper;
    private final Object indexLock = new Object();
    private volatile List<VoiceTriggerRegistration> indexedRegistrations = List.of();
    private volatile List<IrCompiledVoiceTrigger> voiceTriggerIndex = List.of();
    private ModuleProtocolAccess protocol;

    public IrModule(ProtocolRuntime runtime) {
        this(runtime, IrNamedObjectEnhancer.noop());
    }

    public IrModule(ProtocolRuntime runtime, IrNamedObjectEnhancer namedObjectEnhancer) {
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
        refreshVoiceTriggerIndex(protocol.voiceTriggers().registrations());
        adapter.subscribeAsrFinalText(this::handleAsrFinalText);
        adapter.registerParseCapability(this::handleParseRequest);
    }

    private void handleAsrFinalText(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof AsrTextPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "ASR payload is invalid", null);
            return;
        }
        processInput(envelope, IrInputMapper.fromAsr(payload));
    }

    private void handleParseRequest(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof IrParsePayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "IR payload is invalid", null);
            return;
        }
        processInput(envelope, IrInputMapper.fromParse(payload));
    }

    private void processInput(TianshuEnvelope envelope, IrInputText input) {
        if (input == null || input.blank()) {
            publishNoMatch(envelope, input, "EMPTY_INPUT");
            return;
        }
        IrPreparedInput prepared = preprocessor.prepare(input);
        IrNamedObjectEnhancementResult namedObjectEnhancement = namedObjectEnhancer.enhance(prepared);
        List<IrCompiledVoiceTrigger> index = ensureVoiceTriggerIndex();
        IrInputText voiceInput = wakeWordEnhancer.enhance(inputWithNamedObjectRepair(prepared, namedObjectEnhancement), index);
        IrMatchBatch batch = matcher.match(voiceInput, index);
        IrRoutingDecision decision = routingPolicy.decide(voiceInput, batch);
        if (decision.kind() == IrRouteKind.NO_MATCH) {
            publishNoMatch(envelope, voiceInput, decision.reason());
            return;
        }
        submitDialogueArbitration(envelope, voiceInput, prepared, namedObjectEnhancement, batch);
        publishDialogueRouted(envelope, voiceInput, batch);
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
        List<VoiceTriggerRegistration> registrations = protocol.voiceTriggers().registrations();
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

}
