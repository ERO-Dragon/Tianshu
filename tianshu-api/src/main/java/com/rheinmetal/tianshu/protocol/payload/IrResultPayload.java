package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerMatch;

import java.util.List;

public record IrResultPayload(
        String repairedText,
        String normalizedText,
        List<VoiceTriggerMatch> voiceMatches,
        List<String> matchedItemIds,
        List<String> matchedEntityTypeIds,
        int turnId,
        long sessionId,
        long timestampMillis
) implements ITianshuPayload {
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
}
