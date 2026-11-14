package com.rheinmetal.tianshu.function.echo.settings;

import com.rheinmetal.tianshu.core.runtime.RuntimeRefreshReason;

public interface TtsSettingsRuntimeActions {
    void stopPlaybackResources();

    void restartRuntime(RuntimeRefreshReason reason);
}
