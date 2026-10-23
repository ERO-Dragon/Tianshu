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
import com.rheinmetal.tianshu.protocol.payload.IrModuleMatch;
import com.rheinmetal.tianshu.protocol.payload.IrObjectMatch;
import com.rheinmetal.tianshu.protocol.payload.IrResultPayload;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerMatch;
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
                System.currentTimeMillis(),
                displayNames(namedObjectEnhancement),
                input.rawText(),
                objectMatches(namedObjectEnhancement),
                moduleMatches(batch)
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

    /**
     * Pairs every matched id with the readable name the enhancer found for it.
     *
     * <p>The repair step matches spoken words against names, so it already knows both; carrying the names along
     * keeps the id from travelling alone and being turned back into a name by whoever reads it.</p>
     */
    /**
     * Turns the enhancer's parallel id/name lists into one entry per matched object.
     *
     * <p>The spoken word is the name the matching word list carried — that is the word the utterance was matched
     * against, not the canonical display name invented afterwards. The language key is derived from the registry
     * kind and the id, so a consumer holding a language can render it without knowing our conventions.</p>
     */
    private static List<IrObjectMatch> objectMatches(IrNamedObjectEnhancementResult enhancement) {
        List<IrObjectMatch> matches = new java.util.ArrayList<>();
        addObjectMatches(matches, enhancement.matchedItemIds(), enhancement.matchedItemNames(), IrObjectMatch.KIND_ITEM);
        addObjectMatches(matches, enhancement.matchedEntityTypeIds(), enhancement.matchedEntityNames(),
                IrObjectMatch.KIND_ENTITY);
        return List.copyOf(matches);
    }

    private static void addObjectMatches(List<IrObjectMatch> output, List<String> ids, List<String> names, String kind) {
        if (ids == null || names == null) {
            return;
        }
        int size = Math.min(ids.size(), names.size());
        for (int index = 0; index < size; index++) {
            String id = ids.get(index) == null ? "" : ids.get(index).trim();
            String spoken = names.get(index) == null ? "" : names.get(index).trim();
            if (id.isEmpty() || spoken.isEmpty()) {
                continue;
            }
            output.add(new IrObjectMatch(spoken, id, kind, kind + "." + id.replace(':', '.').replace('/', '.')));
        }
    }

    /**
     * One entry per trigger word a module match consumed.
     *
     * <p>Trigger words are matched literally, so the word that was said and the word the module registered are the
     * same string; when that stops being true the matcher has to carry the spoken fragment and this has to change.</p>
     */
    private static List<IrModuleMatch> moduleMatches(IrMatchBatch batch) {
        List<IrModuleMatch> matches = new java.util.ArrayList<>();
        if (batch == null || batch.matches() == null) {
            return List.of();
        }
        for (VoiceTriggerMatch match : batch.matches()) {
            if (match == null) {
                continue;
            }
            addModuleMatches(matches, match, match.matchedWakeWords());
            addModuleMatches(matches, match, match.matchedExtraWords());
        }
        return List.copyOf(matches);
    }

    private static void addModuleMatches(List<IrModuleMatch> output, VoiceTriggerMatch match, List<String> words) {
        if (words == null) {
            return;
        }
        for (String word : words) {
            String registered = word == null ? "" : word.trim();
            if (!registered.isEmpty()) {
                output.add(new IrModuleMatch(match.moduleId(), registered, registered));
            }
        }
    }
    private static Map<String, String> displayNames(IrNamedObjectEnhancementResult enhancement) {
        Map<String, String> names = new java.util.LinkedHashMap<>();
        pairNames(enhancement.matchedItemIds(), enhancement.matchedItemNames(), names);
        pairNames(enhancement.matchedEntityTypeIds(), enhancement.matchedEntityNames(), names);
        return names;
    }

    private static void pairNames(List<String> ids, List<String> names, Map<String, String> output) {
        if (ids == null || names == null) {
            return;
        }
        int size = Math.min(ids.size(), names.size());
        for (int index = 0; index < size; index++) {
            String id = ids.get(index) == null ? "" : ids.get(index).trim();
            String name = names.get(index) == null ? "" : names.get(index).trim();
            if (!id.isEmpty() && !name.isEmpty()) {
                output.putIfAbsent(id, name);
            }
        }
    }
    private void publishDiagnostic(String code, DiagnosticSeverity severity, Map<String, String> attributes) {
        diagnostics.publish(DiagnosticEvent.now(AsrProtocolAdapter.MODULE_ID, code, severity, DiagnosticPrivacy.RAW_CONTENT, attributes));
    }
}
