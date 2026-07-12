package com.rheinmetal.tianshu.function.auxilium.core.output;

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
}
