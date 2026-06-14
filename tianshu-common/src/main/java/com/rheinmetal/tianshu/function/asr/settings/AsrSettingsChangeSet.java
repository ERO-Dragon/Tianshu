package com.rheinmetal.tianshu.function.asr.settings;

import java.util.Objects;

public record AsrSettingsChangeSet(
        boolean enabledChanged,
        boolean disabled,
        boolean enabledNow,
        boolean micChanged,
        boolean modelChanged,
        boolean triggerChanged,
        boolean audioPipelineChanged
) {
    public static AsrSettingsChangeSet between(AsrSettingsSnapshot before, AsrSettingsSnapshot after) {
        boolean enabledChanged = before.enabled() != after.enabled();
        return new AsrSettingsChangeSet(
                enabledChanged,
                enabledChanged && !after.enabled(),
                enabledChanged && after.enabled(),
                !Objects.equals(before.selectedMicName(), after.selectedMicName()),
                !Objects.equals(before.modelName(), after.modelName()),
                before.triggerMode() != after.triggerMode(),
                before.highPassFilterEnabled() != after.highPassFilterEnabled()
                        || before.rnnoiseEnabled() != after.rnnoiseEnabled()
                        || before.vadEnabled() != after.vadEnabled()
        );
    }

    public boolean requiresRuntimeReload() {
        return enabledChanged || modelChanged;
    }

    public boolean requiresAudioReconfiguration() {
        return disabled || enabledNow || micChanged || audioPipelineChanged;
    }
}
