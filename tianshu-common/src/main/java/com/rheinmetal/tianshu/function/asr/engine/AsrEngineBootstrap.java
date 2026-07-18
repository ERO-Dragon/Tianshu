package com.rheinmetal.tianshu.function.asr.engine;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.asr.settings.AsrConfiguration;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.function.asr.AsrRuntimeCapabilities;
import com.rheinmetal.tianshu.model.AsrModelInfo;
import com.rheinmetal.tianshu.model.AsrModelManager;
import com.rheinmetal.tianshu.model.ModelFilesMissingException;
import com.rheinmetal.tianshu.utils.PathUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

public final class AsrEngineBootstrap {
    private final IGameEnvironment env;
    private final AsrConfiguration config;
    private final Consumer<AsrEngineBootstrapStatus> statusSink;

    public AsrEngineBootstrap(IGameEnvironment env, AsrConfiguration config) {
        this(env, config, null);
    }

    public AsrEngineBootstrap(IGameEnvironment env, AsrConfiguration config, Consumer<AsrEngineBootstrapStatus> statusSink) {
        this.env = env;
        this.config = config;
        this.statusSink = statusSink == null ? ignored -> {} : statusSink;
    }

    public AsrEngine initialize(ModuleRuntimeContext context, String moduleId) {
        Path modelPath = config.getAsrModelPath();
        if (modelPath == null || modelPath.getFileName() == null || modelPath.getFileName().toString().isBlank()) {
            markFailed(context, moduleId, "ASR model is not configured");
            publishStatus(AsrEngineBootstrapStatus.failed("tianshu.presence.module.asr.not_configured"));
            return null;
        }
        if (modelPath == null || !Files.isDirectory(modelPath)) {
            markFailed(context, moduleId, "ASR model is not installed");
            env.info("asr.model.directory_missing");
            publishStatus(AsrEngineBootstrapStatus.waiting("tianshu.presence.module.asr.not_installed"));
            return null;
        }

        AsrEngine engine = new AsrEngine(env);
        try {
            if (initializeEngine(engine, context, modelPath)) {
                context.runtimeState().capabilities().markReady(AsrRuntimeCapabilities.INPUT, moduleId);
                env.info("asr.engine.initialized");
                publishStatus(AsrEngineBootstrapStatus.ready("tianshu.presence.module.asr.ready"));
                return engine;
            }
            markFailed(context, moduleId, "ASR 引擎初始化失败");
            publishStatus(AsrEngineBootstrapStatus.failed("tianshu.presence.module.asr.failed"));
        } catch (ModelFilesMissingException e) {
            markFailed(context, moduleId, "ASR 模型文件缺失: " + e.getMessage());
            env.error("asr.model.files_missing", e);
            publishStatus(AsrEngineBootstrapStatus.failed("tianshu.presence.module.asr.model_files_missing"));
        } catch (RuntimeException | LinkageError failure) {
            markFailed(context, moduleId, "ASR 引擎初始化异常: " + failure.getMessage());
            env.error("asr.engine.initialization_failed", failure);
            publishStatus(AsrEngineBootstrapStatus.failed("tianshu.presence.module.asr.exception"));
        }
        engine.shutdown();
        return null;
    }

    private boolean initializeEngine(AsrEngine engine, ModuleRuntimeContext context, Path modelPath) throws ModelFilesMissingException {
        File safeDir = PathUtils.getSafeModelDir(modelPath.toFile());
        if (safeDir == null) {
            env.error("asr.model.path_unavailable", null);
            return false;
        }

        AsrModelInfo modelInfo = resolveModelInfo(modelPath);
        if (modelInfo == null) {
            env.error("ASR model is not declared in asr_model.json: " + modelPath.getFileName(), null);
            return false;
        }

        Path hotwordsFile = AsrHotwordSupport.fromModel(modelInfo).reloadRequired() ? resolveHotwordsFile(context, modelInfo) : null;
        boolean initialized = engine.initialize(modelInfo, safeDir.toPath(), hotwordsFile);
        if (!initialized) {
            env.error("asr.model.unsupported", null);
        publishStatus(AsrEngineBootstrapStatus.failed("tianshu.presence.module.asr.unsupported_model"));
        }
        return initialized;
    }

    private AsrModelInfo resolveModelInfo(Path modelPath) {
        String dirName = modelPath.getFileName() != null ? modelPath.getFileName().toString() : "";
        return AsrModelManager.getModelByLocalKey(dirName);
    }

    private Path resolveHotwordsFile(ModuleRuntimeContext context, AsrModelInfo modelInfo) {
        String language = resolveHotwordLanguage(modelInfo);
        if ("en".equals(language)) {
            return context.voiceResources().snapshot().enHotwordsFile();
        }
        return context.voiceResources().snapshot().zhHotwordsFile();
    }

    private String resolveHotwordLanguage(AsrModelInfo modelInfo) {
        if (modelInfo == null) {
            return "zh";
        }
        for (String lang : modelInfo.getLang()) {
            if (lang == null) {
                continue;
            }
            String normalized = lang.trim().toLowerCase();
            if (normalized.startsWith("en")) {
                return "en";
            }
            if (normalized.startsWith("zh") || normalized.startsWith("cmn") || normalized.startsWith("cn")) {
                return "zh";
            }
        }
        return "zh";
    }

    private void markFailed(ModuleRuntimeContext context, String moduleId, String reason) {
        context.runtimeState().capabilities().markFailed(AsrRuntimeCapabilities.INPUT, moduleId, reason);
    }

    private void publishStatus(AsrEngineBootstrapStatus status) {
        statusSink.accept(status);
    }
}
