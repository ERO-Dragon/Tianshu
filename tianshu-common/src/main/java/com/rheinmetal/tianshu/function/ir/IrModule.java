package com.rheinmetal.tianshu.function.ir;

import com.rheinmetal.tianshu.function.ir.core.IRBaseUtils;
import com.rheinmetal.tianshu.function.ir.core.IRParseResult;
import com.rheinmetal.tianshu.function.ir.core.ParseUnit;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.AsrTextPayload;
import com.rheinmetal.tianshu.protocol.payload.IrParsePayload;
import com.rheinmetal.tianshu.protocol.payload.LlmPromptPayload;
import com.rheinmetal.tianshu.protocol.payload.VoiceTriggerPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerMatch;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerRegistration;

import java.util.ArrayList;
import java.util.List;

public final class IrModule {
    private final IrProtocolAdapter adapter;
    private final IrCommandParser commandParser;
    private final ProtocolRuntime runtime;

    public IrModule(ProtocolRuntime runtime, IrCommandParser commandParser) {
        this.runtime = runtime;
        this.adapter = new IrProtocolAdapter(runtime);
        this.commandParser = commandParser == null ? IrCommandParser.unavailable() : commandParser;
    }

    public void register() {
        adapter.subscribeAsrFinalText(this::handleAsrFinalText);
        adapter.registerParseCapability(this::handleParseRequest);
    }

    private void handleAsrFinalText(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof AsrTextPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "ASR payload is invalid", null);
            return;
        }
        parseAndRoute(envelope, context, new IrParsePayload(payload.text(), payload.rawText(), payload.turnId(), payload.sessionId(), "asr", 0, true));
    }

    private void handleParseRequest(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof IrParsePayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "IR payload is invalid", null);
            return;
        }
        parseAndRoute(envelope, context, payload);
    }

    private void parseAndRoute(TianshuEnvelope envelope, ProtocolContext context, IrParsePayload payload) {
        String normalizedText = payload.text().trim();
        if (normalizedText.isEmpty()) {
            return;
        }

        IRParseResult parseResult = commandParser.parse(normalizedText, true);
        String routedText = routeText(parseResult == null ? "" : parseResult.getHealedRawText(), normalizedText);
        boolean dispatched = dispatchVoiceTriggers(envelope, routedText, parseResult);
        if (!dispatched) {
            routeToChat(envelope, context, routedText);
        }
    }

    private void routeToChat(TianshuEnvelope envelope, ProtocolContext context, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        context.submit(adapter.requestLlmChat(envelope, new LlmPromptPayload(text.trim(), "")));
    }

    private boolean dispatchVoiceTriggers(TianshuEnvelope envelope, String text, IRParseResult parseResult) {
        List<VoiceTriggerMatch> matches = matchVoiceTriggersWithIrTokens(text);
        if (matches.isEmpty()) {
            return false;
        }
        String sourceText = text.trim();
        List<String> itemNames = matchedItemNames(parseResult);
        List<String> itemIds = matchedItemIds(parseResult);
        for (VoiceTriggerMatch match : matches) {
            adapter.dispatchVoiceTrigger(envelope, match.moduleId(), new VoiceTriggerPayload(sourceText, match.moduleId(), match.matchedHotwords(), match.matchedExtraWords(), itemNames, itemIds, match.confidence()));
        }
        return true;
    }

    private List<VoiceTriggerMatch> matchVoiceTriggersWithIrTokens(String text) {
        String tokenText = IRBaseUtils.joinTokens(IRBaseUtils.tokenize(text));
        if (tokenText.isBlank()) {
            return List.of();
        }
        List<VoiceTriggerMatch> matches = new ArrayList<>();
        for (VoiceTriggerRegistration registration : runtime.voiceTriggers().registrations()) {
            List<String> matchedHotwords = collectTokenMatches(tokenText, registration.hotwords());
            List<String> matchedExtraWords = collectTokenMatches(tokenText, registration.extraWords());
            if (matchedHotwords.isEmpty() && matchedExtraWords.isEmpty()) {
                continue;
            }
            matches.add(new VoiceTriggerMatch(registration.moduleId(), matchedHotwords, matchedExtraWords, voiceTriggerConfidence(registration, matchedHotwords, matchedExtraWords)));
        }
        return matches;
    }

    private List<String> collectTokenMatches(String tokenText, List<String> words) {
        if (words == null || words.isEmpty()) {
            return List.of();
        }
        List<String> matches = new ArrayList<>();
        for (String word : words) {
            String wordTokenText = IRBaseUtils.joinTokens(IRBaseUtils.tokenize(word));
            if (!wordTokenText.isBlank() && tokenText.contains(wordTokenText)) {
                matches.add(word);
            }
        }
        return matches;
    }

    private double voiceTriggerConfidence(VoiceTriggerRegistration registration, List<String> matchedHotwords, List<String> matchedExtraWords) {
        int total = Math.max(1, registration.hotwords().size() + registration.extraWords().size());
        double score = matchedHotwords.size() * 2.0D + matchedExtraWords.size();
        return Math.min(1.0D, score / Math.max(2.0D, total));
    }

    private List<String> matchedItemNames(IRParseResult parseResult) {
        if (parseResult == null) {
            return List.of();
        }
        List<String> itemNames = new ArrayList<>();
        if (parseResult.getBestCandidateText() != null && !parseResult.getBestCandidateText().isBlank()) {
            itemNames.add(parseResult.getBestCandidateText().trim());
        }
        return itemNames;
    }

    private List<String> matchedItemIds(IRParseResult parseResult) {
        if (parseResult == null) {
            return List.of();
        }
        List<String> itemIds = new ArrayList<>();
        if (parseResult.getBestCandidateRealItemId() != null && !parseResult.getBestCandidateRealItemId().isBlank()) {
            itemIds.add(parseResult.getBestCandidateRealItemId().trim());
        }
        for (ParseUnit unit : parseResult.getUnits()) {
            if (unit.targetRealItemId != null && !unit.targetRealItemId.isBlank()) {
                itemIds.add(unit.targetRealItemId.trim());
            }
        }
        return itemIds;
    }

    private String routeText(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        if (fallback != null) {
            return fallback.trim();
        }
        return "";
    }
}
