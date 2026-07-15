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
            publishStatus(AsrEngineBootstrapStatus.failed("tianshu.presence.module.asr.not_configured", "ASR 模型未配置"));
            return null;
        }
        if (modelPath == null || !Files.isDirectory(modelPath)) {
            markFailed(context, moduleId, "ASR model is not installed");
            env.info("ASR 模型目录不存在，静默等待");
            publishStatus(AsrEngineBootstrapStatus.waiting("tianshu.presence.module.asr.not_installed", "ASR 模型未安装"));
            return null;
        }

        AsrEngine engine = new AsrEngine(env);
        try {
            if (initializeEngine(engine, context, modelPath)) {
                context.runtimeState().capabilities().markReady(AsrRuntimeCapabilities.INPUT, moduleId);
                env.info("ASR 引擎初始化成功");
                publishStatus(AsrEngineBootstrapStatus.ready("tianshu.presence.module.asr.ready", "态势感知已就绪"));
                return engine;
            }
            markFailed(context, moduleId, "ASR 引擎初始化失败");
            publishStatus(AsrEngineBootstrapStatus.failed("tianshu.presence.module.asr.failed", "ASR 引擎初始化失败"));
        } catch (ModelFilesMissingException e) {
            markFailed(context, moduleId, "ASR 模型文件缺失: " + e.getMessage());
            env.error("ASR 模型文件缺失: " + e.getMessage(), null);
            publishStatus(AsrEngineBootstrapStatus.failed("tianshu.presence.module.asr.model_files_missing", "ASR 模型文件缺失，请重新下载"));
        } catch (RuntimeException | LinkageError failure) {
            markFailed(context, moduleId, "ASR 引擎初始化异常: " + failure.getMessage());
            env.error("ASR 引擎初始化失败", failure);
            publishStatus(AsrEngineBootstrapStatus.failed("tianshu.presence.module.asr.exception", "ASR 引擎初始化异常"));
        }
        engine.shutdown();
        return null;
    }

    private boolean initializeEngine(AsrEngine engine, ModuleRuntimeContext context, Path modelPath) throws ModelFilesMissingException {
        File safeDir = PathUtils.getSafeModelDir(modelPath.toFile());
        if (safeDir == null) {
            env.error("获取安全模型目录失败", null);
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
            env.error("ASR 引擎初始化失败，模型类型可能尚未适配", null);
            publishStatus(AsrEngineBootstrapStatus.failed("tianshu.presence.module.asr.unsupported_model", "ASR 引擎初始化失败，该模型类型尚未适配"));
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
