package com.rheinmetal.tianshu.protocol.voice;

import java.util.List;

public record VoiceTriggerRegistration(
        String moduleId,
        List<String> hotwords,
        List<String> extraWords,
        VoiceCommandCategory category,
        int priority,
        VoiceCommandScope scope,
        boolean dialogueEligible
) {
    public VoiceTriggerRegistration(String moduleId, List<String> hotwords, List<String> extraWords) {
        this(moduleId, hotwords, extraWords, VoiceCommandCategory.GENERAL, 0, VoiceCommandScope.CLIENT, false);
    }

    public VoiceTriggerRegistration {
        moduleId = normalizeRequired(moduleId, "moduleId");
        hotwords = TextListNormalizer.normalize(hotwords);
        extraWords = TextListNormalizer.normalize(extraWords);
        category = category == null ? VoiceCommandCategory.GENERAL : category;
        scope = scope == null ? VoiceCommandScope.CLIENT : scope;
        if (hotwords.isEmpty() && extraWords.isEmpty()) {
            throw new IllegalArgumentException("voice trigger registration must contain at least one word");
        }
    }

    public List<String> commandWords() {
        return extraWords;
    }

    private static String normalizeRequired(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value.trim();
    }
}
