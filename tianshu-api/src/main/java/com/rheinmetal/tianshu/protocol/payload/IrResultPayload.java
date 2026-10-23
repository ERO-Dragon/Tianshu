package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerMatch;

import java.util.List;
import java.util.Map;

public record IrResultPayload(
        String repairedText,
        String normalizedText,
        List<VoiceTriggerMatch> voiceMatches,
        List<String> matchedItemIds,
        List<String> matchedEntityTypeIds,
        int turnId,
        long sessionId,
        long timestampMillis,
        /**
         * Display names for the ids matched above, keyed by raw id.
         *
         * <p>The repair step already knows the readable name of everything it matched, and that name is what a
         * prompt should contain; without it the id travels alone and the reader has to rebuild the name.</p>
         */
        Map<String, String> displayNames,
        /** The utterance as the recogniser produced it, before any repair. */
        String rawText,
        /** Named objects the words were matched to, each with its own evidence. */
        List<IrObjectMatch> objectMatches,
        /** Module trigger words the utterance hit, each with the word that module registered. */
        List<IrModuleMatch> moduleMatches
) implements ITianshuPayload {
    public IrResultPayload(
            String repairedText,
            String normalizedText,
            List<VoiceTriggerMatch> voiceMatches,
            List<String> matchedItemIds,
            List<String> matchedEntityTypeIds,
            int turnId,
            long sessionId,
            long timestampMillis
    ) {
        this(repairedText, normalizedText, voiceMatches, matchedItemIds, matchedEntityTypeIds,
                turnId, sessionId, timestampMillis, Map.of(), "", List.of(), List.of());
    }

    public IrResultPayload(
            String repairedText,
            String normalizedText,
            List<VoiceTriggerMatch> voiceMatches,
            List<String> matchedItemIds,
            List<String> matchedEntityTypeIds,
            int turnId,
            long sessionId,
            long timestampMillis,
            Map<String, String> displayNames
    ) {
        this(repairedText, normalizedText, voiceMatches, matchedItemIds, matchedEntityTypeIds,
                turnId, sessionId, timestampMillis, displayNames, "", List.of(), List.of());
    }

    public IrResultPayload {
        if (repairedText == null) repairedText = "";
        repairedText = repairedText.trim();
        if (normalizedText == null) normalizedText = "";
        normalizedText = normalizedText.trim();
        voiceMatches = voiceMatches == null || voiceMatches.isEmpty() ? List.of() : List.copyOf(voiceMatches);
        matchedItemIds = normalize(matchedItemIds);
        matchedEntityTypeIds = normalize(matchedEntityTypeIds);
        sessionId = Math.max(0L, sessionId);
        timestampMillis = timestampMillis > 0L ? timestampMillis : System.currentTimeMillis();
        displayNames = normalizeNames(displayNames);
        rawText = rawText == null ? "" : rawText.trim();
        objectMatches = objectMatches == null ? List.of() : List.copyOf(objectMatches);
        moduleMatches = moduleMatches == null ? List.of() : List.copyOf(moduleMatches);
    }

    private static List<String> normalize(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static Map<String, String> normalizeNames(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            String name = entry.getValue() == null ? "" : entry.getValue().trim();
            if (!key.isEmpty() && !name.isEmpty()) {
                normalized.put(key, name);
            }
        }
        return Map.copyOf(normalized);
    }
}
