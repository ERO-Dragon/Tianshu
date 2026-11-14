package com.rheinmetal.tianshu.function.auxilium.core.output;

import com.rheinmetal.tianshu.function.auxilium.speech.AXSpeechUtterance;

public interface AuxiliumOutputSettings {
    AuxiliumOutputSettings DEFAULT = () -> AuxiliumOutputMode.UI_ONLY;

    AuxiliumOutputMode outputMode();

    /** Voice parameters for spoken output; plain values, so no speech-module type appears here. */
    default AXSpeechUtterance.VoiceProfile voiceProfile() {
        return AXSpeechUtterance.VoiceProfile.defaults();
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
