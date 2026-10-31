package com.rheinmetal.tianshu.function.echo.settings;

import java.util.Objects;

public record TtsSettingsChangeSet(
        boolean enabledChanged,
        boolean disabled,
        boolean modelChanged
) {
    public static TtsSettingsChangeSet between(TtsSettingsSnapshot before, TtsSettingsSnapshot after) {
        if (before == null || after == null) {
            return new TtsSettingsChangeSet(false, false, false);
        }
        boolean enabledChanged = before.enabled() != after.enabled();
        return new TtsSettingsChangeSet(
                enabledChanged,
                enabledChanged && !after.enabled(),
                !Objects.equals(before.modelName(), after.modelName()) || !Objects.equals(before.modelPath(), after.modelPath())
        );
    }

    public boolean requiresRuntimeRestart() {
        return enabledChanged || modelChanged;
    }
}
