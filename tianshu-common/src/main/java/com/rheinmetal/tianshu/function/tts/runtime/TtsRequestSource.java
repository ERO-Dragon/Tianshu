package com.rheinmetal.tianshu.function.tts.runtime;

public enum TtsRequestSource {
    ASSISTANT,
    ALERT,
    PREVIEW,
    SYSTEM,
    UI,
    UNKNOWN;

    public static TtsRequestSource from(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return TtsRequestSource.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return UNKNOWN;
        }
    }
}
