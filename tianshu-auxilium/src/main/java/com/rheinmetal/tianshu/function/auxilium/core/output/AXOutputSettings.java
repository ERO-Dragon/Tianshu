package com.rheinmetal.tianshu.function.auxilium.core.output;

import com.rheinmetal.tianshu.protocol.payload.TtsVoiceOptions;

public interface AXOutputSettings {
    AXOutputSettings DEFAULT = () -> AXOutputMode.UI_ONLY;

    AXOutputMode outputMode();

    default TtsVoiceOptions ttsVoiceOptions() {
        return TtsVoiceOptions.defaults();
    }

    default boolean uiEnabled() {
        AXOutputMode mode = outputMode();
        return mode != null && mode.uiEnabled();
    }

    default boolean ttsEnabled() {
        AXOutputMode mode = outputMode();
        return mode != null && mode.ttsEnabled();
    }
}
