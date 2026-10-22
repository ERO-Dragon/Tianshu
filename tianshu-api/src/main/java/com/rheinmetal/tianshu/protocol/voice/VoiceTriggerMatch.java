package com.rheinmetal.tianshu.protocol.voice;

import java.util.List;

public record VoiceTriggerMatch(String moduleId, List<String> matchedWakeWords, List<String> matchedExtraWords, double confidence) {
    public VoiceTriggerMatch {
        if (moduleId == null || moduleId.isBlank()) {
            throw new IllegalArgumentException("moduleId cannot be blank");
        }
        moduleId = moduleId.trim();
        matchedWakeWords = TextListNormalizer.normalize(matchedWakeWords);
        matchedExtraWords = TextListNormalizer.normalize(matchedExtraWords);
        confidence = Math.max(0.0D, Math.min(1.0D, confidence));
    }
}
