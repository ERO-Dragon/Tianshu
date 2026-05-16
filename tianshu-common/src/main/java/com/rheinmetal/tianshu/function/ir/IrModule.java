package com.rheinmetal.tianshu.function.ir;

import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.AsrTextPayload;
import com.rheinmetal.tianshu.protocol.payload.IrParsePayload;
import com.rheinmetal.tianshu.protocol.payload.IrResultPayload;
import com.rheinmetal.tianshu.protocol.payload.VoiceTriggerPayload;
import com.rheinmetal.tianshu.protocol.runtime.ModuleProtocolAccess;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerRegistration;
import com.rheinmetal.tianshu.function.ir.enhance.IrItemEnhancementResult;
import com.rheinmetal.tianshu.function.ir.enhance.IrItemEnhancer;
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
    private final IrItemEnhancer itemEnhancer;
    private final IrVoiceTriggerIndexer indexer;
    private final IrVoiceTriggerMatcher matcher;
    private final IrRoutingPolicy routingPolicy;
    private final IrDialogueArbitrationRequestMapper dialogueMapper;
    private final Object indexLock = new Object();
    private volatile List<VoiceTriggerRegistration> indexedRegistrations = List.of();
    private volatile List<IrCompiledVoiceTrigger> voiceTriggerIndex = List.of();
    private ModuleProtocolAccess protocol;

    public IrModule(ProtocolRuntime runtime) {
        this(runtime, IrItemEnhancer.noop());
    }

    public IrModule(ProtocolRuntime runtime, IrItemEnhancer itemEnhancer) {
        this.adapter = new IrProtocolAdapter(runtime);
        this.preprocessor = new IrInputPreprocessor();
        this.itemEnhancer = itemEnhancer == null ? IrItemEnhancer.noop() : itemEnhancer;
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
        IrItemEnhancementResult itemEnhancement = itemEnhancer.enhance(prepared);
        IrInputText voiceInput = prepared.voiceInput();
        List<IrCompiledVoiceTrigger> index = ensureVoiceTriggerIndex();
        IrMatchBatch batch = matcher.match(voiceInput, index);
        IrRoutingDecision decision = routingPolicy.decide(voiceInput, batch);
        if (decision.kind() == IrRouteKind.NO_MATCH) {
            publishNoMatch(envelope, voiceInput, decision.reason());
            return;
        }
        if (decision.kind() == IrRouteKind.DIALOGUE_ARBITRATION) {
            requestDialogueArbitration(envelope, voiceInput, prepared, itemEnhancement, batch);
            publishDialogueRouted(envelope, voiceInput);
            return;
        }
        for (IrVoiceMatch match : batch.matches()) {
            dispatch(envelope, voiceInput, match, itemEnhancement);
        }
        publishMatched(envelope, voiceInput, batch.matches());
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

    private void requestDialogueArbitration(TianshuEnvelope envelope, IrInputText voiceInput, IrPreparedInput prepared,
                                            IrItemEnhancementResult itemEnhancement, IrMatchBatch batch) {
        adapter.requestDialogueArbitration(envelope, dialogueMapper.map(
                voiceInput,
                prepared,
                itemEnhancement,
                batch
        ));
    }

    private void dispatch(TianshuEnvelope envelope, IrInputText input, IrVoiceMatch match, IrItemEnhancementResult itemEnhancement) {
        adapter.dispatchVoiceTrigger(envelope, match.moduleId(), new VoiceTriggerPayload(
                input.rawText(),
                input.text(),
                match.moduleId(),
                match.matchedHotwords(),
                match.matchedExtraWords(),
                input.source(),
                match.confidence(),
                itemEnhancement == null ? List.of() : itemEnhancement.matchedItemNames(),
                itemEnhancement == null ? List.of() : itemEnhancement.matchedItemIds(),
                List.of(),
                input.createdAt(),
                input.sessionId(),
                input.turnId()
        ));
    }

    private void publishMatched(TianshuEnvelope envelope, IrInputText input, List<IrVoiceMatch> matches) {
        adapter.publishResult(envelope, new IrResultPayload(
                true,
                input.text(),
                "VOICE_TRIGGER",
                targetSummary(matches),
                maxConfidence(matches),
                false,
                "MATCHED",
                input.turnId(),
                input.sessionId()
        ));
    }

    private void publishDialogueRouted(TianshuEnvelope envelope, IrInputText input) {
        adapter.publishResult(envelope, new IrResultPayload(
                true,
                "",
                "DIALOGUE_ARBITRATION",
                "",
                0.0D,
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
                "VOICE_TRIGGER",
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
