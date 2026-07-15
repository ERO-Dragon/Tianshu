package com.rheinmetal.tianshu.function.asr.settings;

import com.rheinmetal.tianshu.constant.TriggerMode;

public record AsrSettingsSnapshot(
        boolean enabled,
        String selectedMicName,
        TriggerMode triggerMode,
        String modelName,
        boolean highPassFilterEnabled,
        boolean rnnoiseEnabled,
        boolean vadEnabled
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
                config.getTriggerMode(),
                modelName,
                config.isAsrHighPassFilterEnabled(),
                config.isAsrRnnoiseEnabled(),
                config.isAsrVadEnabled()
        );
    }

    private static String resolveModelName(AsrConfiguration config) {
        String customName = config.getCustomAsrName();
        return customName == null ? "" : customName.trim();
    }
}
