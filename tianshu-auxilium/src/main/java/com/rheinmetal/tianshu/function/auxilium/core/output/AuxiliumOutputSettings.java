package com.rheinmetal.tianshu.function.auxilium.core.output;

import com.rheinmetal.tianshu.protocol.payload.TtsVoiceOptions;

public interface AuxiliumOutputSettings {
    AuxiliumOutputSettings DEFAULT = () -> AuxiliumOutputMode.UI_ONLY;

    AuxiliumOutputMode outputMode();

    default TtsVoiceOptions ttsVoiceOptions() {
        return TtsVoiceOptions.defaults();
    }

    default boolean uiEnabled() {
        AuxiliumOutputMode mode = outputMode();
        return mode != null && mode.uiEnabled();
    }

    default boolean ttsEnabled() {
        AuxiliumOutputMode mode = outputMode();
        return mode != null && mode.ttsEnabled();
    }
}
