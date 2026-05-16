package com.rheinmetal.tianshu.function.asr.settings;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.core.runtime.RuntimeRefreshReason;

public final class AsrSettingsApplier {
    private final AsrSettingsRuntimeActions runtimeActions;
    private final IAudioBridge audioBridge;

    public AsrSettingsApplier(AsrSettingsRuntimeActions runtimeActions, IAudioBridge audioBridge) {
        this.runtimeActions = runtimeActions;
        this.audioBridge = audioBridge;
    }

    public void apply(AsrSettingsSnapshot before, AsrSettingsSnapshot after) {
        if (before == null || after == null) {
            return;
        }
        AsrSettingsChangeSet changeSet = AsrSettingsChangeSet.between(before, after);
        apply(changeSet, after);
    }

    public void apply(AsrSettingsChangeSet changeSet, AsrSettingsSnapshot after) {
        if (changeSet == null || after == null) {
            return;
        }
        if (changeSet.disabled()) {
            runtimeActions.releaseVoiceInputResources();
            return;
        }
        if (changeSet.micChanged()) {
            audioBridge.selectMic(after.selectedMicName());
        }
        if (changeSet.enabledNow()) {
            audioBridge.ensureHardwareRunning();
        }
        if (changeSet.requiresRuntimeReload()) {
            runtimeActions.restartRuntime(RuntimeRefreshReason.RESOURCE_CHANGED);
        }
    }
}
