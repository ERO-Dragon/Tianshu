package com.rheinmetal.tianshu.function.tts.settings;

import com.rheinmetal.tianshu.core.runtime.RuntimeRefreshReason;

public interface TtsSettingsRuntimeActions {
    void stopPlaybackResources();

    void restartRuntime(RuntimeRefreshReason reason);
}
