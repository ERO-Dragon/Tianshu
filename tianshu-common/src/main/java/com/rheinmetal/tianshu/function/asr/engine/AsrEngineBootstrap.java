package com.rheinmetal.tianshu.function.asr.engine;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.function.asr.AsrRuntimeCapabilities;
import com.rheinmetal.tianshu.model.AsrModelInfo;
import com.rheinmetal.tianshu.model.AsrModelManager;
import com.rheinmetal.tianshu.model.ModelFilesMissingException;
import com.rheinmetal.tianshu.utils.PathUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AsrEngineBootstrap {
    private final IGameEnvironment env;
    private final ITianshuConfig config;

    public AsrEngineBootstrap(IGameEnvironment env, ITianshuConfig config) {
        this.env = env;
        this.config = config;
    }

    public AsrEngine initialize(ModuleRuntimeContext context, String moduleId) {
        return initialize(context, moduleId, true);
    }

    public AsrEngine initialize(ModuleRuntimeContext context, String moduleId, boolean notifyPlayer) {
        Path modelPath = config.getAsrModelPath();
        if (modelPath == null || !Files.isDirectory(modelPath)) {
            env.info("ASR 模型目录不存在，静默等待");
            return null;
        }

        AsrEngine engine = new AsrEngine(env);
        try {
            if (initializeEngine(engine, context, modelPath)) {
                context.runtimeState().capabilities().markReady(AsrRuntimeCapabilities.INPUT, moduleId);
                env.info("ASR 引擎初始化成功");
                if (notifyPlayer) {
                    env.executeOnMainThread(() -> env.displayMessageToPlayer("§b[天极] §f态势感知已就绪"));
                }
                return engine;
            }
            markFailed(context, moduleId, "ASR 引擎初始化失败");
        } catch (ModelFilesMissingException e) {
            markFailed(context, moduleId, "ASR 模型文件缺失: " + e.getMessage());
            env.error("ASR 模型文件缺失: " + e.getMessage(), null);
            env.executeOnMainThread(() -> env.displayMessageToPlayer("§b[天极] §cASR 模型文件缺失，请重新下载"));
        } catch (Throwable t) {
            markFailed(context, moduleId, "ASR 引擎初始化异常: " + t.getMessage());
            env.error("ASR 引擎初始化失败", t);
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
            env.executeOnMainThread(() -> env.displayMessageToPlayer("§b[天极] §cASR 引擎初始化失败，该模型类型尚未适配"));
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
}
