package com.rheinmetal.tianshu.function.tts.synthesis;

import java.util.Objects;
import java.util.function.Supplier;

final class TtsActiveModelSelection {
    private final Supplier<String> configuredModelName;
    private volatile String activeModelName = "";

    TtsActiveModelSelection(Supplier<String> configuredModelName) {
        this.configuredModelName = Objects.requireNonNull(configuredModelName, "configuredModelName");
    }

    String currentModelName() {
        String active = normalize(activeModelName);
        return active.isEmpty() ? normalize(configuredModelName.get()) : active;
    }

    void activate(String modelName) {
        String normalized = normalize(modelName);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("TTS model name is required");
        }
        activeModelName = normalized;
    }

    void clear() {
        activeModelName = "";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
