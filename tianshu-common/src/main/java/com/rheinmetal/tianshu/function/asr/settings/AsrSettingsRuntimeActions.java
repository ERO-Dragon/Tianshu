package com.rheinmetal.tianshu.function.asr.settings;

import com.rheinmetal.tianshu.core.runtime.RuntimeRefreshReason;

public interface AsrSettingsRuntimeActions {
    void releaseVoiceInputResources();

    void reconfigureAudioPipeline();

    void restartRuntime(RuntimeRefreshReason reason);
}
