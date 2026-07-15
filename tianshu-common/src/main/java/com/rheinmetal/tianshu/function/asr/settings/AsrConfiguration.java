package com.rheinmetal.tianshu.function.asr.settings;

import com.rheinmetal.tianshu.constant.TriggerMode;

import java.nio.file.Path;

public interface AsrConfiguration {
    boolean isAsrEnabled();

    TriggerMode getTriggerMode();

    String getSelectedMicName();

    boolean isAsrRnnoiseEnabled();

    boolean isAsrHighPassFilterEnabled();

    boolean isAsrVadEnabled();

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
