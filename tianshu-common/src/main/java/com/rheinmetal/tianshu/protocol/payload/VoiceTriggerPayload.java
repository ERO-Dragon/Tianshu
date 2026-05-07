package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerMatch;

import java.util.List;

public record VoiceTriggerPayload(String sourceText, String moduleId, List<String> matchedHotwords, List<String> matchedExtraWords, List<String> matchedItemNames, List<String> matchedItemIds, double confidence) implements ITianshuPayload {
    public VoiceTriggerPayload {
        if (sourceText == null) sourceText = "";
        VoiceTriggerMatch match = new VoiceTriggerMatch(moduleId, matchedHotwords, matchedExtraWords, confidence);
        moduleId = match.moduleId();
        matchedHotwords = match.matchedHotwords();
        matchedExtraWords = match.matchedExtraWords();
        matchedItemNames = normalize(matchedItemNames);
        matchedItemIds = normalize(matchedItemIds);
        confidence = match.confidence();
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
