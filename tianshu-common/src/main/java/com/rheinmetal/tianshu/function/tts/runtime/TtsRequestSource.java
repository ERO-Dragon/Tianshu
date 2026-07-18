package com.rheinmetal.tianshu.function.tts.runtime;

public record TtsRequestSource(String value) {
    public static final TtsRequestSource ALERT = of("module.tts.alert");
    public static final TtsRequestSource PREVIEW = of("module.tts.preview");
    public static final TtsRequestSource SYSTEM = of("module.tts.system");
    public static final TtsRequestSource UI = of("module.tts.ui");
    public static final TtsRequestSource UNKNOWN = of("unknown");

    public TtsRequestSource {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("TTS request source cannot be blank");
        }
        value = value.trim();
    }

    public static TtsRequestSource of(String value) {
        return new TtsRequestSource(value);
    }

    public static TtsRequestSource from(String value) {
        return value == null || value.isBlank() ? UNKNOWN : of(value);
    }
}
