package com.rheinmetal.tianshu.function.echo.settings;

import com.rheinmetal.tianshu.function.echo.model.TtsModelInfo;
import com.rheinmetal.tianshu.function.echo.model.TtsModelLayout;

import java.nio.file.Path;

public record TtsSettingsSnapshot(
        boolean enabled,
        String modelName,
        Path modelPath
) {
    public static TtsSettingsSnapshot from(EchoConfiguration config) {
        return new TtsSettingsSnapshot(
                config != null && config.isTtsEnabled(),
                config == null ? "" : safe(config.getCustomTtsName()),
                config == null ? null : selectedModelPath(config)
        );
    }

    private static Path selectedModelPath(EchoConfiguration config) {
        String modelName = safe(config.getCustomTtsName());
        if (modelName.isEmpty()) {
            return null;
        }
        TtsModelInfo info = TtsModelInfo.findByName(modelName);
        return info != null
                ? TtsModelLayout.modelDirectory(config.getTtsBasePath(), info)
                : TtsModelLayout.modelDirectory(config.getTtsBasePath(), modelName);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
