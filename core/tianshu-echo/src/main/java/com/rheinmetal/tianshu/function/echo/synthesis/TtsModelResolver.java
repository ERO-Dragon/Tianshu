package com.rheinmetal.tianshu.function.echo.synthesis;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.echo.TtsModelService;
import com.rheinmetal.tianshu.function.echo.model.TtsModelInfo;

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
        // The settings page calls a model installed through this same predicate, so a directory that
        // merely exists must not resolve: otherwise the runtime tries to load a model the user was
        // told is not installed and fails deeper, with a native-load error instead of "not installed".
        // A null entry is a legacy/custom directory the catalog knows nothing about; it keeps resolving.
        if (info != null && !TtsModelInfo.isModelDirectoryComplete(info, modelDir)) {
            env.warn("tts.model.directory_incomplete: " + modelDir);
            return Optional.empty();
        }
        return Optional.of(new TtsResolvedModel(modelDir, info, TtsResolvedModel.resolveBackendType(info)));
    }
}
