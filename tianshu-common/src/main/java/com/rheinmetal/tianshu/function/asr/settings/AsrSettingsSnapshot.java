package com.rheinmetal.tianshu.function.asr.settings;

import com.rheinmetal.tianshu.constant.TriggerMode;

import java.nio.file.Path;

public record AsrSettingsSnapshot(
        boolean enabled,
        String selectedMicName,
        TriggerMode triggerMode,
        String wakeWord,
        String modelName,
        boolean rnnoiseEnabled,
        boolean vadEnabled
) {
    public AsrSettingsSnapshot {
        selectedMicName = selectedMicName == null ? "" : selectedMicName;
        wakeWord = wakeWord == null ? "" : wakeWord;
        modelName = modelName == null ? "" : modelName.trim();
    }

    public static AsrSettingsSnapshot from(com.rheinmetal.tianshu.api.ITianshuConfig config) {
        String modelName = resolveModelName(config);
        return new AsrSettingsSnapshot(
                config.isAsrEnabled(),
                config.getSelectedMicName(),
                config.getTriggerMode(),
                config.getWakeWord(),
                modelName,
                config.isAsrRnnoiseEnabled(),
                config.isAsrVadEnabled()
        );
    }

    private static String resolveModelName(com.rheinmetal.tianshu.api.ITianshuConfig config) {
        String customName = config.getCustomAsrName();
        if (customName != null && !customName.isBlank()) {
            return customName;
        }
        Path modelPath = config.getAsrModelPath();
        return modelPath != null && modelPath.getFileName() != null ? modelPath.getFileName().toString() : "";
    }
}
