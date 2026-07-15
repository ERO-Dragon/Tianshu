package com.rheinmetal.tianshu.function.tts.settings;


import java.nio.file.Path;

public record TtsSettingsSnapshot(
        boolean enabled,
        String modelName,
        Path modelPath
) {
    public static TtsSettingsSnapshot from(TtsConfiguration config) {
        return new TtsSettingsSnapshot(
                config != null && config.isTtsEnabled(),
                config == null ? "" : safe(config.getCustomTtsName()),
                config == null ? null : selectedModelPath(config)
        );
    }

    private static Path selectedModelPath(TtsConfiguration config) {
        String modelName = safe(config.getCustomTtsName());
        if (modelName.isEmpty()) {
            return null;
        }
        Path modelRoot = config.getTtsBasePath().resolve("model").normalize();
        Path modelPath = modelRoot.resolve(modelName).normalize();
        if (!modelPath.startsWith(modelRoot)) {
            throw new IllegalArgumentException("TTS model selection escapes the model root");
        }
        return modelPath;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
