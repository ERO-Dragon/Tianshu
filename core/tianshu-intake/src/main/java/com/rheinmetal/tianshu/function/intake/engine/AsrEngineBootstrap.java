package com.rheinmetal.tianshu.function.intake.engine;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.intake.settings.IntakeConfiguration;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.function.intake.AsrRuntimeCapabilities;
import com.rheinmetal.tianshu.function.intake.model.AsrModelInfo;
import com.rheinmetal.tianshu.function.intake.model.AsrModelManager;
import com.rheinmetal.tianshu.model.ModelFilesMissingException;
import com.rheinmetal.tianshu.utils.PathUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

public final class AsrEngineBootstrap {
    private final IGameEnvironment env;
    private final IntakeConfiguration config;
    private final Consumer<AsrEngineBootstrapStatus> statusSink;
    private final Consumer<Boolean> loadingSink;

    public AsrEngineBootstrap(IGameEnvironment env, IntakeConfiguration config) {
        this(env, config, null, null);
    }

    public AsrEngineBootstrap(IGameEnvironment env, IntakeConfiguration config, Consumer<AsrEngineBootstrapStatus> statusSink) {
        this(env, config, statusSink, null);
    }

    public AsrEngineBootstrap(
            IGameEnvironment env,
            IntakeConfiguration config,
            Consumer<AsrEngineBootstrapStatus> statusSink,
            Consumer<Boolean> loadingSink
    ) {
        this.env = env;
        this.config = config;
        this.statusSink = statusSink == null ? ignored -> {} : statusSink;
        this.loadingSink = loadingSink == null ? ignored -> {} : loadingSink;
    }

    public AsrEngine initialize(ModuleRuntimeContext context, String moduleId) {
        if (!config.isAsrEnabled()) {
            context.runtimeState().capabilities().disable(AsrRuntimeCapabilities.MODEL, moduleId);
            return null;
        }
        Path modelPath = config.getAsrModelPath();
        if (modelPath == null || modelPath.getFileName() == null || modelPath.getFileName().toString().isBlank()) {
            markFailed(context, moduleId, "ASR model is not configured");
            publishStatus(AsrEngineBootstrapStatus.failed("tianshu.runtime.module.asr.not_configured"));
            return null;
        }
        if (!Files.isDirectory(modelPath)) {
            markFailed(context, moduleId, "ASR model is not installed");
            env.info("asr.model.directory_missing");
            publishStatus(AsrEngineBootstrapStatus.waiting("tianshu.runtime.module.asr.not_installed"));
            return null;
        }

        AsrEngine engine = new AsrEngine(env);
        EngineFailure failure = initializeEngine(engine, modelPath);
        if (failure == null) {
            context.runtimeState().capabilities().markReady(AsrRuntimeCapabilities.MODEL, moduleId);
            env.info("asr.engine.initialized");
            publishStatus(AsrEngineBootstrapStatus.ready("tianshu.runtime.module.asr.ready"));
            return engine;
        }
        markFailed(context, moduleId, failure.reason());
        publishStatus(AsrEngineBootstrapStatus.failed(failure.messageKey()));
        engine.shutdown();
        return null;
    }

    /**
     * Reads one model into {@code engine}, or reports why it could not.
     *
     * @return {@code null} when the engine is ready, otherwise the one failure the player should be told about
     */
    private EngineFailure initializeEngine(AsrEngine engine, Path modelPath) {
        File safeDir = PathUtils.getSafeModelDir(modelPath.toFile());
        if (safeDir == null) {
            env.error("asr.model.path_unavailable", null);
            return new EngineFailure("tianshu.runtime.module.asr.failed", "ASR model path is unavailable");
        }

        AsrModelInfo modelInfo = resolveModelInfo(modelPath);
        if (modelInfo == null) {
            env.error("ASR model is not declared in asr_model.json: " + modelPath.getFileName(), null);
            return new EngineFailure("tianshu.runtime.module.asr.unsupported_model",
                    "ASR model is not declared in the catalog: " + modelPath.getFileName());
        }
        boolean initialized;
        loadingSink.accept(true);
        try {
            initialized = engine.initialize(modelInfo, safeDir.toPath());
        } catch (ModelFilesMissingException e) {
            env.error("asr.model.files_missing", e);
            return new EngineFailure("tianshu.runtime.module.asr.model_files_missing",
                    "ASR model files are missing: " + e.getMessage());
        } catch (RuntimeException | LinkageError failure) {
            env.error("asr.engine.initialization_failed", failure);
            return new EngineFailure("tianshu.runtime.module.asr.exception",
                    "ASR engine initialization failed: " + failure.getMessage());
        } finally {
            loadingSink.accept(false);
        }
        if (!initialized) {
            env.error("asr.model.unsupported", null);
            String architecture = modelInfo.architecture == null ? "" : modelInfo.architecture.trim();
            return new EngineFailure("tianshu.runtime.module.asr.unsupported_model",
                    "ASR model architecture is not supported: " + architecture);
        }
        return null;
    }

    private AsrModelInfo resolveModelInfo(Path modelPath) {
        String dirName = modelPath.getFileName() != null ? modelPath.getFileName().toString() : "";
        return AsrModelManager.getModelByLocalKey(dirName);
    }

    private void markFailed(ModuleRuntimeContext context, String moduleId, String reason) {
        context.runtimeState().capabilities().markFailed(AsrRuntimeCapabilities.MODEL, moduleId, reason);
    }

    private void publishStatus(AsrEngineBootstrapStatus status) {
        statusSink.accept(status);
    }

    /** The one reason a model read did not succeed; the caller publishes exactly one status for it. */
    private record EngineFailure(String messageKey, String reason) {
    }
}
