package com.rheinmetal.tianshu.function.intake.repair;

import com.rheinmetal.tianshu.api.diagnostics.DiagnosticEvent;
import com.rheinmetal.tianshu.function.intake.AsrProtocolAdapter;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticPrivacy;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSeverity;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink;
import com.rheinmetal.tianshu.function.intake.repair.enhance.IrNamedObjectEnhancementResult;
import com.rheinmetal.tianshu.function.intake.repair.enhance.IrNamedObjectEnhancer;
import com.rheinmetal.tianshu.function.intake.repair.input.IrInputMapper;
import com.rheinmetal.tianshu.function.intake.repair.input.IrInputPreprocessor;
import com.rheinmetal.tianshu.function.intake.repair.input.IrInputText;
import com.rheinmetal.tianshu.function.intake.repair.input.IrPreparedInput;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.function.intake.contract.AsrTextPayload;
import com.rheinmetal.tianshu.function.ia.contract.IrModuleMatch;
import com.rheinmetal.tianshu.function.ia.contract.IrObjectMatch;
import com.rheinmetal.tianshu.function.ia.contract.IrResultPayload;
import com.rheinmetal.tianshu.protocol.trigger.TriggerRegistry;
import com.rheinmetal.tianshu.protocol.trigger.TriggerRegistration;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Repairs one recognised speech turn and publishes what it actually meant.
 *
 * <p>Two views of one utterance travel together. The <em>sentence</em> ({@link IrPreparedInput#voiceText()})
 * keeps every word the player said and is what the payload shows: it is healed for misheard object names
 * and misheard wake words. The <em>matching view</em> ({@link IrPreparedInput#filteredText()}) has fillers
 * and boundary words removed and is only used to resolve objects, so removing a filler can never remove
 * it from the text a prompt or the arbiter reads.</p>
 *
 * <p>Object matches are built as pairs and published through {@code IR.RESULT}; the corrected sentence and
 * the matches are therefore two independent facts instead of one rewritten string that both sides have to
 * interpret.</p>
 *
 * <p>Dropping the presence-context query removed the only asynchronous hop in this lane. The cost is
 * narrow: a bare pronoun ("use this") no longer resolves against the held item. Everything that
 * names the object outright still repairs exactly as before. The dialogue brain collects game
 * context of its own, so the information is not lost, only not duplicated here.</p>
 */
public final class AsrTextRepairer {

    private final AsrProtocolAdapter adapter;
    private final IrInputPreprocessor preprocessor;
    private final IrNamedObjectEnhancer namedObjectEnhancer;
    private final IrWakeWordEnhancer wakeWordEnhancer;
    private final IrTriggerIndexer indexer;
    private final IrTriggerMatcher matcher;
    private final Object indexLock = new Object();

    private volatile List<TriggerRegistration> indexedRegistrations = List.of();
    private volatile List<IrCompiledTrigger> triggerIndex = List.of();
    private volatile TriggerRegistry triggers;
    private volatile boolean acceptingInput;
    private volatile DiagnosticSink diagnostics = DiagnosticSink.NOOP;

    public AsrTextRepairer(AsrProtocolAdapter adapter, IrNamedObjectEnhancer namedObjectEnhancer) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.preprocessor = new IrInputPreprocessor();
        this.namedObjectEnhancer = namedObjectEnhancer == null ? IrNamedObjectEnhancer.noop() : namedObjectEnhancer;
        this.wakeWordEnhancer = new IrWakeWordEnhancer();
        this.indexer = new IrTriggerIndexer();
        this.matcher = new IrTriggerMatcher();
    }

    /** Binds the shared trigger registry and starts accepting repairs; called from the speech module's prepare. */
    public void initialize(TriggerRegistry triggers, DiagnosticSink diagnostics) {
        this.triggers = triggers;
        this.diagnostics = diagnostics == null ? DiagnosticSink.NOOP : diagnostics;
        this.acceptingInput = true;
        refreshTriggerIndex(currentTriggerRegistrations());
    }

    public void stop() {
        acceptingInput = false;
    }

    public void destroy() {
        stop();
        triggers = null;
        indexedRegistrations = List.of();
        triggerIndex = List.of();
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
        List<IrCompiledTrigger> index = ensureTriggerIndex();
        IrInputText healedSentence = prepared.voiceInput().withText(repairedTextOf(namedObjectEnhancement, prepared));
        IrInputText voiceInput = wakeWordEnhancer.enhance(healedSentence, index);
        IrMatchBatch batch = matcher.match(voiceInput, index);
        List<IrObjectMatch> objectMatches = namedObjectEnhancement == null ? List.of() : namedObjectEnhancement.matches();
        adapter.publishIrResult(parent, new IrResultPayload(
                voiceInput.text(),
                prepared.filteredText(),
                input.rawText(),
                objectMatches,
                batch.matches(),
                voiceInput.turnId(),
                voiceInput.sessionId(),
                System.currentTimeMillis()
        ));
        publishDiagnostic("ANALYSIS_PUBLISHED", DiagnosticSeverity.INFO, Map.of(
                "rawText", input.rawText(),
                "repairedText", voiceInput.text(),
                "normalizedText", prepared.filteredText(),
                "moduleMatchCount", Integer.toString(batch.matches().size()),
                "objectMatchCount", Integer.toString(objectMatches.size())
        ));
        // A separate event rather than more arguments on the one above: the debug line for the utterance
        // stays readable when nothing matched, and the match line only appears when there is something to
        // report. The repair lane owns both events; the chat catalogue decides how they read.
        if (!objectMatches.isEmpty() || !batch.matches().isEmpty()) {
            publishDiagnostic("IR_MATCHES", DiagnosticSeverity.INFO, Map.of(
                    "objectMatches", formatObjectMatches(objectMatches),
                    "moduleMatches", formatModuleMatches(batch.matches())
            ));
        }
    }

    /** {@code spoken=registryId} per object, which is exactly what a reader needs to check a repair. */
    private static String formatObjectMatches(List<IrObjectMatch> matches) {
        return matches.stream()
                .map(match -> match.spokenWord() + "=" + match.registryId())
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /** {@code module:spoken=registered(kind)} per trigger, so a wake hit is distinguishable from an extra one. */
    private static String formatModuleMatches(List<IrModuleMatch> matches) {
        return matches.stream()
                .map(match -> match.moduleId() + ":" + match.spokenWord() + "=" + match.registeredWord()
                        + "(" + match.kind() + ")")
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String repairedTextOf(IrNamedObjectEnhancementResult enhancement, IrPreparedInput prepared) {
        String repaired = enhancement == null ? "" : enhancement.repairedText();
        return repaired.isBlank() ? prepared.voiceText() : repaired;
    }

    private List<IrCompiledTrigger> ensureTriggerIndex() {
        List<TriggerRegistration> registrations = currentTriggerRegistrations();
        if (registrations.equals(indexedRegistrations)) {
            return triggerIndex;
        }
        synchronized (indexLock) {
            if (!registrations.equals(indexedRegistrations)) {
                refreshTriggerIndex(registrations);
            }
            return triggerIndex;
        }
    }

    private void refreshTriggerIndex(List<TriggerRegistration> registrations) {
        indexedRegistrations = registrations == null || registrations.isEmpty() ? List.of() : List.copyOf(registrations);
        triggerIndex = indexer.compile(indexedRegistrations);
    }

    private List<TriggerRegistration> currentTriggerRegistrations() {
        TriggerRegistry current = triggers;
        return current == null ? List.of() : current.registrations();
    }

    private void publishDiagnostic(String code, DiagnosticSeverity severity, Map<String, String> attributes) {
        diagnostics.publish(DiagnosticEvent.now(AsrProtocolAdapter.MODULE_ID, code, severity, DiagnosticPrivacy.RAW_CONTENT, attributes));
    }
}