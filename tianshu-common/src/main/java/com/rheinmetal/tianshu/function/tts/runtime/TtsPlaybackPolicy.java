package com.rheinmetal.tianshu.function.tts.runtime;

public enum TtsPlaybackPolicy {
    QUEUE,
    INSERT_AFTER_SESSION,
    INSERT_AFTER_SENTENCE,
    CANCEL_SENTENCE_AND_PLAY,
    CANCEL_SESSION_AND_PLAY,
    REPLACE_CURRENT,
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
