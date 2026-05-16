package com.rheinmetal.tianshu.function.tts.synthesis;

import com.rheinmetal.tianshu.model.TtsModelInfo;

import java.nio.file.Path;

public record TtsResolvedModel(Path modelDir, TtsModelInfo modelInfo, TtsBackendType backendType) {
    public TtsResolvedModel {
        if (modelDir == null) {
            throw new IllegalArgumentException("modelDir cannot be null");
        }
        backendType = backendType == null ? resolveBackendType(modelInfo) : backendType;
    }

    public String engineType() {
        return modelInfo == null ? "legacy" : modelInfo.getEngineType();
    }

    public boolean autoregressive() {
        return backendType.autoregressive();
    }

    public static TtsBackendType resolveBackendType(TtsModelInfo info) {
        if (info != null && "moss".equals(info.getEngineType())) {
            return TtsBackendType.MOSS;
        }
        return TtsBackendType.SHERPA;
    }
}
