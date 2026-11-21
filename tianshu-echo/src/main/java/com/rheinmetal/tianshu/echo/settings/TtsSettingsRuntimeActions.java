package com.rheinmetal.tianshu.echo.settings;

import com.rheinmetal.tianshu.core.runtime.RuntimeRefreshReason;

public interface TtsSettingsRuntimeActions {
    void stopPlaybackResources();

    void restartRuntime(RuntimeRefreshReason reason);
}
