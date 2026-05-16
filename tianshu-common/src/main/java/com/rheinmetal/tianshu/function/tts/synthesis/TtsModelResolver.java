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
        Path modelDir = modelService.resolveCurrentModelDir();
        if (modelDir == null) {
            env.warn("TTS 模型目录未配置");
            return Optional.empty();
        }
        if (!Files.isDirectory(modelDir)) {
            env.warn("TTS 模型目录不存在: " + modelDir);
            return Optional.empty();
        }
        TtsModelInfo info = modelService.resolveCurrentModelInfo();
        return Optional.of(new TtsResolvedModel(modelDir, info, TtsResolvedModel.resolveBackendType(info)));
    }
}
