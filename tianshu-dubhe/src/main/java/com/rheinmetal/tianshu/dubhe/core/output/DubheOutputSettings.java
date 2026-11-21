package com.rheinmetal.tianshu.dubhe.core.output;

import com.rheinmetal.tianshu.dubhe.speech.DubheSpeechUtterance;

public interface DubheOutputSettings {
    DubheOutputSettings DEFAULT = () -> DubheOutputMode.UI_ONLY;

    DubheOutputMode outputMode();

    /** Voice parameters for spoken output; plain values, so no speech-module type appears here. */
    default DubheSpeechUtterance.VoiceProfile voiceProfile() {
        return DubheSpeechUtterance.VoiceProfile.defaults();
    }

    default boolean uiEnabled() {
        DubheOutputMode mode = outputMode();
        return mode != null && mode.uiEnabled();
    }

    default boolean ttsEnabled() {
        DubheOutputMode mode = outputMode();
        return mode != null && mode.ttsEnabled();
    }
}
