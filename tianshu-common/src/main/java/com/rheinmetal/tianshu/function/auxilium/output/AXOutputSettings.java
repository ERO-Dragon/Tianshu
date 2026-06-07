package com.rheinmetal.tianshu.function.auxilium.output;

public interface AXOutputSettings {
    AXOutputSettings DEFAULT = () -> AXOutputMode.UI_ONLY;

    AXOutputMode outputMode();

    default boolean uiEnabled() {
        AXOutputMode mode = outputMode();
        return mode != null && mode.uiEnabled();
    }

    default boolean ttsEnabled() {
        AXOutputMode mode = outputMode();
        return mode != null && mode.ttsEnabled();
    }

    default String ttsVoiceStyle() {
        return "ax";
    }
}
