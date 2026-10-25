package com.rheinmetal.tianshu.function.intake.settings;

import java.nio.file.Path;

public interface IntakeConfiguration {
    boolean isAsrEnabled();

    String getSelectedMicName();

    boolean isAsrRnnoiseEnabled();

    boolean isAsrHighPassFilterEnabled();

    String getCustomAsrName();

    Path getAsrBasePath();

    default Path getAsrModelPath() {
        String modelName = getCustomAsrName();
        if (modelName == null || modelName.isBlank()) {
            return null;
        }
        Path modelRoot = getAsrBasePath().resolve("model").normalize();
        Path modelPath = modelRoot.resolve(modelName.trim()).normalize();
        if (!modelPath.startsWith(modelRoot)) {
            throw new IllegalArgumentException("ASR model selection escapes the model root");
        }
        return modelPath;
    }
}
