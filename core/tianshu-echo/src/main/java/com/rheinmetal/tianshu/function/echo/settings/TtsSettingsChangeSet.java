package com.rheinmetal.tianshu.function.echo.settings;

import com.rheinmetal.tianshu.function.echo.synthesis.cloud.TtsCloudSettings;

import java.util.Objects;

public record TtsSettingsChangeSet(
        boolean enabledChanged,
        boolean disabled,
        boolean modelChanged,
        boolean backendChanged
) {
    public static TtsSettingsChangeSet between(TtsSettingsSnapshot before, TtsSettingsSnapshot after) {
        if (before == null || after == null) {
            return new TtsSettingsChangeSet(false, false, false, false);
        }
        boolean enabledChanged = before.enabled() != after.enabled();
        return new TtsSettingsChangeSet(
                enabledChanged,
                enabledChanged && !after.enabled(),
                !Objects.equals(before.modelName(), after.modelName()) || !Objects.equals(before.modelPath(), after.modelPath()),
                !Objects.equals(before.backendMode(), after.backendMode())
                        || !Objects.equals(before.cloud(), after.cloud())
        );
    }

    public boolean requiresRuntimeRestart() {
        return enabledChanged || modelChanged || backendChanged;
    }
}
