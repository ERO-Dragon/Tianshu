package com.rheinmetal.tianshu.function.intake.settings;

public record AsrSettingsSnapshot(
        boolean enabled,
        String selectedMicName,
        String modelName,
        boolean highPassFilterEnabled,
        boolean rnnoiseEnabled
) {
    public AsrSettingsSnapshot {
        selectedMicName = selectedMicName == null ? "" : selectedMicName;
        modelName = modelName == null ? "" : modelName.trim();
    }

    public static AsrSettingsSnapshot from(AsrConfiguration config) {
        String modelName = resolveModelName(config);
        return new AsrSettingsSnapshot(
                config.isAsrEnabled(),
                config.getSelectedMicName(),
                modelName,
                config.isAsrHighPassFilterEnabled(),
                config.isAsrRnnoiseEnabled()
        );
    }

    private static String resolveModelName(AsrConfiguration config) {
        String customName = config.getCustomAsrName();
        return customName == null ? "" : customName.trim();
    }
}
