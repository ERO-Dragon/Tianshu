package com.rheinmetal.tianshu.function.intake.repair;

import com.rheinmetal.tianshu.api.diagnostics.DiagnosticEvent;
import com.rheinmetal.tianshu.function.intake.AsrProtocolAdapter;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticPrivacy;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSeverity;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink;
import com.rheinmetal.tianshu.function.intake.repair.IrCompiledVoiceTrigger;
import com.rheinmetal.tianshu.function.intake.repair.IrMatchBatch;
import com.rheinmetal.tianshu.function.intake.repair.IrVoiceTriggerIndexer;
import com.rheinmetal.tianshu.function.intake.repair.IrVoiceTriggerMatcher;
import com.rheinmetal.tianshu.function.intake.repair.IrWakeWordEnhancer;
import com.rheinmetal.tianshu.function.intake.repair.enhance.IrNamedObjectEnhancementResult;
import com.rheinmetal.tianshu.function.intake.repair.enhance.IrNamedObjectEnhancer;
import com.rheinmetal.tianshu.function.intake.repair.input.IrInputMapper;
import com.rheinmetal.tianshu.function.intake.repair.input.IrInputPreprocessor;
import com.rheinmetal.tianshu.function.intake.repair.input.IrInputText;
import com.rheinmetal.tianshu.function.intake.repair.input.IrPreparedInput;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.AsrTextPayload;
import com.rheinmetal.tianshu.protocol.payload.IrResultPayload;
import com.rheinmetal.tianshu.protocol.voice.VoiceResourceAccess;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerRegistration;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Repairs one recognised speech turn and publishes what it actually meant.
 *
 * <p>Two steps of a single operation: the recogniser reports what it heard, this component corrects
 * misheard words against the registered voice triggers and game terms, and then reports both the
 * corrected text and what matched. It used to be a standalone module subscribing to the raw text
 * topic and asking the HUD layer for the player's held item before it could answer; it is now a
 * synchronous component owned by the speech module.</p>
 *
 * <p>Dropping the presence-context query removed the only asynchronous hop in this lane. The cost is
 * narrow: a bare pronoun ("use this") no longer resolves against the held item. Everything that
 * names the object outright still repairs exactly as before. The dialogue brain collects game
 * context of its own, so the information is not lost, only not duplicated here.</p>
 *
 * <p>The result still leaves through the public {@code IR.RESULT} topic: consumers that only want
 * the corrected text stay independent of who produced it.</p>
 */
public final class AsrTextRepairer {

    private final AsrProtocolAdapter adapter;
    private final IrInputPreprocessor preprocessor;
    private final IrNamedObjectEnhancer namedObjectEnhancer;
    private final IrWakeWordEnhancer wakeWordEnhancer;
    private final IrVoiceTriggerIndexer indexer;
    private final IrVoiceTriggerMatcher matcher;
    private final Object indexLock = new Object();

    private volatile List<VoiceTriggerRegistration> indexedRegistrations = List.of();
    private volatile List<IrCompiledVoiceTrigger> voiceTriggerIndex = List.of();
    private volatile VoiceResourceAccess voiceResources;
    private volatile boolean acceptingInput;
    private volatile DiagnosticSink diagnostics = DiagnosticSink.NOOP;

    public AsrTextRepairer(AsrProtocolAdapter adapter, IrNamedObjectEnhancer namedObjectEnhancer) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.preprocessor = new IrInputPreprocessor();
        this.namedObjectEnhancer = namedObjectEnhancer == null ? IrNamedObjectEnhancer.noop() : namedObjectEnhancer;
        this.wakeWordEnhancer = new IrWakeWordEnhancer();
        this.indexer = new IrVoiceTriggerIndexer();
        this.matcher = new IrVoiceTriggerMatcher();
    }

    /** Binds the voice-resource view and starts accepting repairs; called from the speech module's prepare. */
    public void initialize(VoiceResourceAccess voiceResources, DiagnosticSink diagnostics) {
        this.voiceResources = voiceResources;
        this.diagnostics = diagnostics == null ? DiagnosticSink.NOOP : diagnostics;
        this.acceptingInput = true;
        refreshVoiceTriggerIndex(currentVoiceTriggerRegistrations());
    }

    public void stop() {
        acceptingInput = false;
    }

    public void destroy() {
        stop();
        voiceResources = null;
        indexedRegistrations = List.of();
        voiceTriggerIndex = List.of();
        diagnostics = DiagnosticSink.NOOP;
    }

    /**
     * Repairs one turn on the calling thread.
     *
     * @param payload the recognised text, as published to {@code INPUT.ASR_FINAL_TEXT}
     * @param parent the envelope that carried the raw text, used as the parent of the result
     */
    public void process(AsrTextPayload payload, TianshuEnvelope parent) {
        if (payload == null || !acceptingInput) {
            return;
        }
        IrInputText input = IrInputMapper.fromAsr(payload);
        if (input == null || input.blank()) {
            publishDiagnostic("EMPTY_INPUT", DiagnosticSeverity.DEBUG, Map.of(
                    "rawText", input == null ? "" : input.rawText()
            ));
            return;
        }
        IrPreparedInput prepared = preprocessor.prepare(input);
        IrNamedObjectEnhancementResult namedObjectEnhancement = namedObjectEnhancer.enhance(prepared);
        List<IrCompiledVoiceTrigger> index = ensureVoiceTriggerIndex();
        IrInputText voiceInput = wakeWordEnhancer.enhance(inputWithNamedObjectRepair(prepared, namedObjectEnhancement), index);
        IrMatchBatch batch = matcher.match(voiceInput, index);
        adapter.publishIrResult(parent, new IrResultPayload(
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
        return List.of();
    }

    private void publishDiagnostic(String code, DiagnosticSeverity severity, Map<String, String> attributes) {
        diagnostics.publish(DiagnosticEvent.now(AsrProtocolAdapter.MODULE_ID, code, severity, DiagnosticPrivacy.RAW_CONTENT, attributes));
    }
}
