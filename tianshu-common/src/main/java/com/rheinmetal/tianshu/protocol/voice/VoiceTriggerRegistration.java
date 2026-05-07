package com.rheinmetal.tianshu.protocol.voice;

import java.util.List;

public record VoiceTriggerRegistration(String moduleId, List<String> hotwords, List<String> extraWords) {
    public VoiceTriggerRegistration {
        moduleId = normalizeRequired(moduleId, "moduleId");
        hotwords = TextListNormalizer.normalize(hotwords);
        extraWords = TextListNormalizer.normalize(extraWords);
        if (hotwords.isEmpty() && extraWords.isEmpty()) {
            throw new IllegalArgumentException("voice trigger registration must contain at least one word");
        }
    }

    private static String normalizeRequired(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value.trim();
    }
}
