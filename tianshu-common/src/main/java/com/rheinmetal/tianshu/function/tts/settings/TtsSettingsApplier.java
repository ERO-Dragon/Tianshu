package com.rheinmetal.tianshu.function.tts.settings;

import com.rheinmetal.tianshu.core.runtime.RuntimeRefreshReason;

public final class TtsSettingsApplier {
    private final TtsSettingsRuntimeActions runtimeActions;

    public TtsSettingsApplier(TtsSettingsRuntimeActions runtimeActions) {
        this.runtimeActions = runtimeActions;
    }

    public void apply(TtsSettingsSnapshot before, TtsSettingsSnapshot after) {
        if (before == null || after == null || runtimeActions == null) {
            return;
        }
        TtsSettingsChangeSet changeSet = TtsSettingsChangeSet.between(before, after);
        if (changeSet.disabled()) {
            runtimeActions.stopPlaybackResources();
        }
        if (changeSet.requiresRuntimeRestart()) {
            runtimeActions.restartRuntime(RuntimeRefreshReason.RESOURCE_CHANGED);
        }
    }
}
