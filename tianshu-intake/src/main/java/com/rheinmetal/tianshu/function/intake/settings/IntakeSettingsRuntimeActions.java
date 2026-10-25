package com.rheinmetal.tianshu.function.intake.settings;

import com.rheinmetal.tianshu.core.runtime.RuntimeRefreshReason;

public interface IntakeSettingsRuntimeActions {
    void releaseVoiceInputResources();

    void reconfigureAudioPipeline();

    void restartRuntime(RuntimeRefreshReason reason);
}
