package com.rheinmetal.tianshu.function.tts.settings;

import com.rheinmetal.tianshu.api.ITianshuConfig;

import java.nio.file.Path;

public record TtsSettingsSnapshot(
        boolean enabled,
        String modelName,
        Path modelPath
) {
    public static TtsSettingsSnapshot from(ITianshuConfig config) {
        return new TtsSettingsSnapshot(
                config != null && config.isTtsEnabled(),
                config == null ? "" : safe(config.getCustomTtsName()),
                config == null ? null : config.getTtsModelPath()
        );
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
