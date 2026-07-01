package com.rheinmetal.tianshu.function.auxilium.core.output;

import java.util.Locale;

public enum AXOutputMode {
    DISABLED(false, false),
    UI_ONLY(true, false),
    TTS_ONLY(false, true),
    UI_AND_TTS(true, true);

    private final boolean uiEnabled;
    private final boolean ttsEnabled;

    AXOutputMode(boolean uiEnabled, boolean ttsEnabled) {
        this.uiEnabled = uiEnabled;
        this.ttsEnabled = ttsEnabled;
    }

    public boolean uiEnabled() {
        return uiEnabled;
    }

    public boolean ttsEnabled() {
        return ttsEnabled;
    }

    public static AXOutputMode fromName(String value, AXOutputMode fallback) {
        if (value == null || value.isBlank()) {
            return fallback == null ? UI_ONLY : fallback;
        }
        try {
            return AXOutputMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback == null ? UI_ONLY : fallback;
        }
    }
}
