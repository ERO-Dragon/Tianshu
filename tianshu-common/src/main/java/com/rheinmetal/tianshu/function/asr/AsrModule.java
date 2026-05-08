package com.rheinmetal.tianshu.function.asr;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.core.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.module.ModuleRuntimeState;
import com.rheinmetal.tianshu.core.module.TianshuManagedModule;
import com.rheinmetal.tianshu.event.TianshuEventBus;
import com.rheinmetal.tianshu.function.asr.engine.AsrEngine;
import com.rheinmetal.tianshu.model.AsrModelInfo;
import com.rheinmetal.tianshu.model.AsrModelManager;
import com.rheinmetal.tianshu.model.ModelFilesMissingException;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.utils.PathUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

public final class AsrModule implements TianshuManagedModule {
    private final IAudioBridge audioBridge;
    private final TianshuEventBus eventBus;
    private final ProtocolRuntime protocolRuntime;
    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final BooleanSupplier voiceInputAcceptance;
    private final LongSupplier interruptProcessing;
    private AsrWorker worker;
    private ModuleRuntimeState runtimeState;
    private AsrModelService modelService;

    public AsrModule(IAudioBridge audioBridge, TianshuEventBus eventBus, ProtocolRuntime protocolRuntime, IGameEnvironment env, ITianshuConfig config, BooleanSupplier voiceInputAcceptance, LongSupplier interruptProcessing) {
        this.audioBridge = audioBridge;
        this.eventBus = eventBus;
        this.protocolRuntime = protocolRuntime;
        this.env = env;
        this.config = config;
        this.voiceInputAcceptance = voiceInputAcceptance;
        this.interruptProcessing = interruptProcessing;
    }

    @Override
    public String moduleId() {
        return "module.asr";
    }

    @Override
    public void register(ModuleRegistrationContext context) {
        modelService = new AsrModelService(env, config, audioBridge, protocolRuntime.executors(), this::asrEngine, this::isAsrReady);
        context.services().register(AsrModelService.class, modelService);
    }

    @Override
    public void prepare(ModuleRuntimeContext context) {
        runtimeState = context.runtimeState();
        initializeEngine(context);
        worker = new AsrWorker(audioBridge, eventBus, protocolRuntime, env, config, this::asrEngine, this::isAsrReady, voiceInputAcceptance, interruptProcessing);
    }

    @Override
    public void start(ModuleRuntimeContext context) {
    }

    @Override
    public void destroy() {
        if (worker != null) {
            worker.stop();
            worker = null;
        }
        if (workerTask != null && !workerTask.isDone()) {
            workerTask.cancel("ASR module destroyed");
            workerTask = null;
        }
        if (runtimeState != null) {
            runtimeState.clearAsrEngine();
            runtimeState = null;
        }
    }

    private AsrEngine asrEngine() {
        return runtimeState == null ? null : runtimeState.asrEngine();
    }

    private boolean isAsrReady() {
        return runtimeState != null && runtimeState.readiness().isAsrReady();
    }

    private void initializeEngine(ModuleRuntimeContext context) {
        if (context.runtimeState().readiness().isAsrReady()) {
            env.info("ASR 引擎已就绪，跳过初始化");
            return;
        }
        try {
            Path originalModelPath = config.getAsrModelPath();
            if (originalModelPath == null || !Files.isDirectory(originalModelPath)) {
                env.info("ASR 模型目录不存在，静默等待");
                return;
            }

            String dirName = originalModelPath.getFileName() != null ? originalModelPath.getFileName().toString() : "";
            AsrModelInfo modelInfo = AsrModelManager.getModelByName(dirName);
            AsrEngine engine = new AsrEngine(env);

            if (modelInfo != null) {
                File safeDir = PathUtils.getSafeModelDir(originalModelPath.toFile());
                if (safeDir == null) {
                    env.error("获取安全模型目录失败", null);
                    return;
                }
                try {
                    Path hotwordsFile = resolveHotwordsFile(context, modelInfo);
                    if (!engine.initialize(modelInfo, safeDir.toPath(), hotwordsFile)) {
                        env.error("ASR 引擎初始化失败，模型类型可能尚未适配", null);
                        env.executeOnMainThread(() -> env.displayMessageToPlayer("§b[天极] §cASR 引擎初始化失败，该模型类型尚未适配"));
                        return;
                    }
                } catch (ModelFilesMissingException e) {
                    env.error("ASR 模型文件缺失: " + e.getMessage(), null);
                    env.executeOnMainThread(() -> env.displayMessageToPlayer("§b[天极] §cASR 模型文件缺失，请重新下载"));
                    return;
                }
            } else {
                File safeDir = PathUtils.getSafeModelDir(originalModelPath.toFile());
                if (safeDir == null) {
                    env.error("获取安全模型目录失败", null);
                    return;
                }
                if (!engine.initialize(safeDir.getAbsolutePath())) {
                    env.error("ASR 引擎初始化失败", null);
                    return;
                }
            }

            context.runtimeState().installAsrEngine(engine);
            env.info("ASR 引擎初始化成功");
            env.executeOnMainThread(() -> env.displayMessageToPlayer("§b[天极] §f态势感知已就绪"));
        } catch (Throwable t) {
            env.error("ASR 引擎初始化失败", t);
        }
    }

    private Path resolveHotwordsFile(ModuleRuntimeContext context, AsrModelInfo modelInfo) {
        String language = resolveHotwordLanguage(modelInfo);
        if ("en".equals(language)) {
            return context.voiceResources().enHotwordsFile();
        }
        return context.voiceResources().zhHotwordsFile();
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
}
