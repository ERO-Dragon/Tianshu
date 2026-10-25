package com.rheinmetal.tianshu.function.intake.settings;


public record IntakeSettingsSnapshot(
        boolean enabled,
        String selectedMicName,
        String modelName,
        boolean highPassFilterEnabled,
        boolean rnnoiseEnabled
) {
    public IntakeSettingsSnapshot {
        selectedMicName = selectedMicName == null ? "" : selectedMicName;
        modelName = modelName == null ? "" : modelName.trim();
    }

    public static IntakeSettingsSnapshot from(IntakeConfiguration config) {
        String modelName = resolveModelName(config);
        return new IntakeSettingsSnapshot(
                config.isAsrEnabled(),
                config.getSelectedMicName(),
                modelName,
                config.isAsrHighPassFilterEnabled(),
                config.isAsrRnnoiseEnabled()
        );
    }

    private static String resolveModelName(IntakeConfiguration config) {
        String customName = config.getCustomAsrName();
        return customName == null ? "" : customName.trim();
    }
}
