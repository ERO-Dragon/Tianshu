package com.rheinmetal.tianshu.function.tts.synthesis;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.tts.TtsModelService;
import com.rheinmetal.tianshu.model.TtsModelInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class TtsModelResolver {
    private final IGameEnvironment env;
    private final TtsModelService modelService;

    public TtsModelResolver(IGameEnvironment env, TtsModelService modelService) {
        this.env = env;
        this.modelService = modelService;
    }

    public Optional<TtsResolvedModel> resolveCurrent() {
        return resolve(modelService.currentConfiguredModelName());
    }

    public Optional<TtsResolvedModel> resolve(String modelName) {
        Path modelDir = modelService.resolveModelDir(modelName);
        if (modelDir == null) {
            return Optional.empty();
        }
        if (!Files.isDirectory(modelDir)) {
            env.warn("tts.model.directory_missing: " + modelDir);
            return Optional.empty();
        }
        TtsModelInfo info = modelService.resolveModelInfo(modelName);
        return Optional.of(new TtsResolvedModel(modelDir, info, TtsResolvedModel.resolveBackendType(info)));
    }
}
