package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerMatch;

import java.util.List;

public record VoiceTriggerPayload(
        String sourceText,
        String normalizedText,
        String moduleId,
        List<String> matchedHotwords,
        List<String> matchedCommandWords,
        String sourceChannel,
        double confidence,
        List<String> matchedItemNames,
        List<String> matchedItemIds,
        List<String> matchedEntityRefs,
        long timestamp,
        long sessionId,
        int turnId
) implements ITianshuPayload {
    public VoiceTriggerPayload(String sourceText, String moduleId, List<String> matchedHotwords, List<String> matchedExtraWords, List<String> matchedItemNames, List<String> matchedItemIds, double confidence) {
        this(sourceText, normalizeText(sourceText), moduleId, matchedHotwords, matchedExtraWords, "", confidence, matchedItemNames, matchedItemIds, List.of(), System.currentTimeMillis(), 0L, 0);
    }

    public VoiceTriggerPayload {
        if (sourceText == null) sourceText = "";
        sourceText = sourceText.trim();
        if (normalizedText == null || normalizedText.isBlank()) normalizedText = normalizeText(sourceText);
        VoiceTriggerMatch match = new VoiceTriggerMatch(moduleId, matchedHotwords, matchedCommandWords, confidence);
        moduleId = match.moduleId();
        matchedHotwords = match.matchedHotwords();
        matchedCommandWords = match.matchedExtraWords();
        if (sourceChannel == null) sourceChannel = "";
        sourceChannel = sourceChannel.trim();
        confidence = match.confidence();
        matchedItemNames = normalize(matchedItemNames);
        matchedItemIds = normalize(matchedItemIds);
        matchedEntityRefs = normalize(matchedEntityRefs);
        if (timestamp <= 0L) timestamp = System.currentTimeMillis();
    }

    public List<String> matchedExtraWords() {
        return matchedCommandWords;
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

    private static String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.trim().toLowerCase(java.util.Locale.ROOT);
        normalized = normalized.replaceAll("\\s+", " ");
        return normalized;
    }
}
