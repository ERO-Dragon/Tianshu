package com.rheinmetal.tianshu.intake.settings;

import com.rheinmetal.tianshu.core.runtime.RuntimeRefreshReason;

public interface IntakeSettingsRuntimeActions {
    void releaseVoiceInputResources();

    void reconfigureAudioPipeline();

    void restartRuntime(RuntimeRefreshReason reason);
}
