package com.rheinmetal.tianshu.function.tts;

import com.rheinmetal.tianshu.core.runtime.RuntimeCapability;

public final class TtsRuntimeCapabilities {
    public static final RuntimeCapability SYNTHESIS = RuntimeCapability.of("capability.tts.synthesis");
    public static final RuntimeCapability PLAYBACK = RuntimeCapability.of("capability.tts.playback");
    public static final RuntimeCapability MODEL_MANAGEMENT = RuntimeCapability.of("capability.tts.model_management");
    public static final RuntimeCapability VOICE_LIBRARY = RuntimeCapability.of("capability.tts.voice_library");

    private TtsRuntimeCapabilities() {
    }
}
