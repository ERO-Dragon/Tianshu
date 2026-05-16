package com.rheinmetal.tianshu.function.ir;

import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerDeliveryTarget;

import java.util.List;

public record IrVoiceMatch(String moduleId, List<String> matchedHotwords, List<String> matchedExtraWords, double confidence, int priority, VoiceTriggerDeliveryTarget deliveryTarget) {
    public IrVoiceMatch(String moduleId, List<String> matchedHotwords, List<String> matchedExtraWords, double confidence) {
        this(moduleId, matchedHotwords, matchedExtraWords, confidence, 0, null);
    }

    public IrVoiceMatch(String moduleId, List<String> matchedHotwords, List<String> matchedExtraWords, double confidence, int priority) {
        this(moduleId, matchedHotwords, matchedExtraWords, confidence, priority, null);
    }

    public IrVoiceMatch {
        if (moduleId == null) moduleId = "";
        moduleId = moduleId.trim();
        matchedHotwords = normalize(matchedHotwords);
        matchedExtraWords = normalize(matchedExtraWords);
        confidence = Math.max(0.0D, Math.min(1.0D, confidence));
        deliveryTarget = deliveryTarget == null ? VoiceTriggerDeliveryTarget.defaultFor(moduleId) : deliveryTarget;
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
