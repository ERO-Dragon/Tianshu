package com.rheinmetal.tianshu.function.ir;

import java.util.List;

public record IrVoiceMatch(String moduleId, List<String> matchedWakeWords, List<String> matchedExtraWords, double confidence, int priority) {
    public IrVoiceMatch(String moduleId, List<String> matchedWakeWords, List<String> matchedExtraWords, double confidence) {
        this(moduleId, matchedWakeWords, matchedExtraWords, confidence, 0);
    }

    public IrVoiceMatch {
        if (moduleId == null) moduleId = "";
        moduleId = moduleId.trim();
        matchedWakeWords = normalize(matchedWakeWords);
        matchedExtraWords = normalize(matchedExtraWords);
        confidence = Math.max(0.0D, Math.min(1.0D, confidence));
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
