package com.rheinmetal.tianshu.protocol.dialogue.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;
import com.rheinmetal.tianshu.protocol.payload.IrModuleMatch;
import com.rheinmetal.tianshu.protocol.payload.IrObjectMatch;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerMatch;

import java.util.List;
import java.util.Map;

public record DialogueArbitrationRequestPayload(
        String requestId,
        String sourceModuleId,
        String playerId,
        String turnId,
        long sourceSessionId,
        String repairedText,
        String normalizedText,
        List<VoiceTriggerMatch> voiceMatches,
        List<String> matchedItemIds,
        List<String> matchedEntityTypeIds,
        long timestampMillis,
        long expireAtMillis,
        /** Display names for the matched ids, keyed by raw id: the name is what a prompt should show. */
        Map<String, String> displayNames,
        /** Named objects the utterance was matched to, with the words that were said. */
        List<IrObjectMatch> objectMatches,
        /** Module trigger words the utterance hit. */
        List<IrModuleMatch> moduleMatches
) implements ITianshuPayload {
    public DialogueArbitrationRequestPayload(
            String requestId,
            String sourceModuleId,
            String playerId,
            String turnId,
            long sourceSessionId,
            String repairedText,
            String normalizedText,
            List<VoiceTriggerMatch> voiceMatches,
            List<String> matchedItemIds,
            List<String> matchedEntityTypeIds,
            long timestampMillis,
            long expireAtMillis
    ) {
        this(requestId, sourceModuleId, playerId, turnId, sourceSessionId, repairedText, normalizedText,
                voiceMatches, matchedItemIds, matchedEntityTypeIds, timestampMillis, expireAtMillis,
                Map.of(), List.of(), List.of());
    }

    public DialogueArbitrationRequestPayload {
        requestId = requireText(requestId, "requestId");
        sourceModuleId = requireText(sourceModuleId, "sourceModuleId");
        playerId = requireText(playerId, "playerId");
        turnId = sanitize(turnId);
        sourceSessionId = Math.max(0L, sourceSessionId);
        repairedText = sanitize(repairedText);
        normalizedText = sanitize(normalizedText);
        voiceMatches = voiceMatches == null || voiceMatches.isEmpty() ? List.of() : List.copyOf(voiceMatches);
        matchedItemIds = copyTextList(matchedItemIds);
        matchedEntityTypeIds = copyTextList(matchedEntityTypeIds);
        timestampMillis = Math.max(0L, timestampMillis);
        expireAtMillis = Math.max(0L, expireAtMillis);
        displayNames = displayNames == null ? Map.of() : Map.copyOf(displayNames);
        objectMatches = objectMatches == null ? List.of() : List.copyOf(objectMatches);
        moduleMatches = moduleMatches == null ? List.of() : List.copyOf(moduleMatches);
    }

    public boolean expiredAt(long nowMillis) {
        return expireAtMillis > 0L && expireAtMillis <= nowMillis;
    }

    public List<String> matchedWakeWords() {
        return voiceMatches.stream()
                .flatMap(match -> match.matchedWakeWords().stream())
                .distinct()
                .toList();
    }

    public List<String> matchedExtraWords() {
        return voiceMatches.stream()
                .flatMap(match -> match.matchedExtraWords().stream())
                .distinct()
                .toList();
    }

    private static List<String> copyTextList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).toList());
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value.trim();
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}
