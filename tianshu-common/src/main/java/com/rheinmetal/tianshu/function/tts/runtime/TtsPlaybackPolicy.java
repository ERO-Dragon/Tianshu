package com.rheinmetal.tianshu.function.tts.runtime;

public enum TtsPlaybackPolicy {
    QUEUE,
    REPLACE_CURRENT,
    INTERRUPT_LOWER_PRIORITY,
    DROP_IF_BUSY,
    LATEST_ONLY;

    public static TtsPlaybackPolicy from(String value) {
        if (value == null || value.isBlank()) {
            return QUEUE;
        }
        try {
            return TtsPlaybackPolicy.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return QUEUE;
        }
    }
}
