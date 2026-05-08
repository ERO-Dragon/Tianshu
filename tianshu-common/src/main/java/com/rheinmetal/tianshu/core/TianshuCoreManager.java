package com.rheinmetal.tianshu.core;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.INativeLibBridge;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.constant.VramTier;
import com.rheinmetal.tianshu.core.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.module.ModuleRuntimeState;
import com.rheinmetal.tianshu.core.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.module.TianshuModuleHost;
import com.rheinmetal.tianshu.function.asr.AsrModelService;
import com.rheinmetal.tianshu.function.asr.AsrModule;
import com.rheinmetal.tianshu.function.asr.engine.AsrEngine;
import com.rheinmetal.tianshu.event.InterruptEvent;
import com.rheinmetal.tianshu.event.TianshuEventBus;
import com.rheinmetal.tianshu.function.chatassistant.ChatAssistantModule;
import com.rheinmetal.tianshu.function.ir.IrCommandParser;
import com.rheinmetal.tianshu.function.ir.IrModule;
import com.rheinmetal.tianshu.function.llm.LlmModule;
import com.rheinmetal.tianshu.function.llm.server.LlmServerProcessManager;
import com.rheinmetal.tianshu.function.tts.TtsModule;
import com.rheinmetal.tianshu.function.tts.TtsModelService;
import com.rheinmetal.tianshu.function.ui.UiProtocolBridge;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolBootstrap;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;
import com.rheinmetal.tianshu.protocol.voice.VoiceResourceManager;
import com.rheinmetal.tianshu.protocol.voice.VoiceResourceSnapshot;
import com.rheinmetal.tianshu.model.AsrModelInfo;
import com.rheinmetal.tianshu.model.ModelManager;
import com.rheinmetal.tianshu.model.ModelSetupService;
import com.rheinmetal.tianshu.model.TtsModelInfo;
import com.rheinmetal.tianshu.function.llm.LlmEngineProvider;
import com.rheinmetal.tianshu.function.tts.TtsWorker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;


public class TianshuCoreManager {


    public interface DownloadProgressCallback {
        void onProgress(String label, int percent);
        void onComplete();
        void onError(String message);
    }

    public interface PreviewAsrCallback {
        void onReady();
        void onResult(String text);
        void onError(String message);
        void onFinish();
    }

    public interface PreviewTtsCallback {
        void onReady();
        void onPlaying();
        void onError(String message);
        void onFinish();
    }

    public enum EnginePhase {
        IDLE,
        INITIALIZING,
        PARTIALLY_READY,
        FULLY_READY,
        RESTARTING,
        DESTROYED
    }

    public static class State {
        private volatile boolean asrReady = false;
        private volatile boolean llmReady = false;
        private volatile boolean ttsReady = false;
        private volatile EnginePhase phase = EnginePhase.IDLE;

        public boolean isAsrReady() { return asrReady; }
        public boolean isLlmReady() { return llmReady; }
        public boolean isTtsReady() { return ttsReady; }
        public EnginePhase getPhase() { return phase; }

        public boolean isAnyReady() { return asrReady || llmReady || ttsReady; }
        public boolean isAllReady() { return asrReady && llmReady && ttsReady; }

        public void setAsrReady(boolean v) {
            asrReady = v;
            refreshPhase();
        }
        public void setLlmReady(boolean v) {
            llmReady = v;
            refreshPhase();
        }
        public void setTtsReady(boolean v) {
            ttsReady = v;
            refreshPhase();
        }

        public void setPhase(EnginePhase p) { phase = p; }

        public void reset() {
            asrReady = false;
            llmReady = false;
            ttsReady = false;
            phase = EnginePhase.IDLE;
        }

        public void refreshPhase() {
            if (asrReady && llmReady && ttsReady) {
                phase = EnginePhase.FULLY_READY;
            } else if (asrReady || llmReady || ttsReady) {
                phase = EnginePhase.PARTIALLY_READY;
            }
        }
    }

    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final INativeLibBridge nativeLibBridge;
    private final IAudioBridge audioBridge;
    private final TianshuEventBus eventBus;
    private final ProtocolRuntime protocolRuntime;
    private final TianshuModuleHost moduleHost;
    private final ModuleServiceRegistry moduleServices;
    private final VoiceResourceManager voiceResourceManager;
    private final ModuleRuntimeState runtimeState;
    private final State state;
    private final EnvSetupManager envSetupManager;
    private final LlmServerProcessManager processManager;
    private final TianshuThreadPool threadPool;
    private final ModelManager modelManager;
    private final ModelSetupService modelSetupService;

    private LlmEngineProvider llmEngineProvider;
    private IrCommandParser irCommandParser = IrCommandParser.unavailable();


    private volatile boolean initialized = false;
    private volatile boolean downloadPaused = false;
    private volatile boolean isRestarting = false;

    public TianshuCoreManager(IGameEnvironment env, ITianshuConfig config, INativeLibBridge nativeLibBridge, IAudioBridge audioBridge) {
        this.env = env;
        this.config = config;
        this.nativeLibBridge = nativeLibBridge;
        this.audioBridge = audioBridge;
        this.eventBus = new TianshuEventBus(env);
        this.protocolRuntime = ProtocolBootstrap.create(env::executeOnMainThread);
        this.moduleHost = new TianshuModuleHost();
        this.moduleServices = new ModuleServiceRegistry();
        this.voiceResourceManager = new VoiceResourceManager(env, config);
        this.runtimeState = new ModuleRuntimeState();
        this.state = runtimeState.readiness();
        this.envSetupManager = new EnvSetupManager(env, nativeLibBridge, protocolRuntime.executors());
        this.processManager = new LlmServerProcessManager(env, config, nativeLibBridge, protocolRuntime.executors(), () -> {
            state.setLlmReady(true);
            env.executeOnMainThread(() -> env.displayMessageToPlayer("§b[天极] §f中枢核心已就绪"));
        });
        this.threadPool = new TianshuThreadPool(env);
        this.modelManager = new ModelManager(config);
        this.modelSetupService = new ModelSetupService(env, config, protocolRuntime.executors(), () -> moduleServices.require(AsrModelService.class));
    }

    public State getState() {
        return state;
    }

    public TianshuEventBus getEventBus() {
        return eventBus;
    }

    public ProtocolRuntime getProtocolRuntime() {
        return protocolRuntime;
    }

    public ModuleServiceRegistry services() {
        return moduleServices;
    }

    public void setIrCommandParser(IrCommandParser irCommandParser) {
        this.irCommandParser = irCommandParser == null ? IrCommandParser.unavailable() : irCommandParser;
    }

    public IAudioBridge getAudioBridge() {
        return audioBridge;
    }

    public TianshuThreadPool getThreadPool() {
        return threadPool;
    }

    public EnvSetupManager getEnvSetupManager() {
        return envSetupManager;
    }

    public LlmServerProcessManager getProcessManager() {
        return processManager;
    }

    public ModelManager getModelManager() {
        return modelManager;
    }

    public AsrEngine getAsrEngine() {
        return runtimeState.asrEngine();
    }

    public boolean isEngineReady() {
        return state.isAllReady() && initialized;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public boolean isAsrReady() {
        return state.isAsrReady();
    }

    public boolean isLlmReady() {
        return state.isLlmReady();
    }

    public boolean isTtsReady() {
        return state.isTtsReady();
    }

    public EnginePhase getEnginePhase() {
        return state.getPhase();
    }

    public boolean canAcceptVoiceInput() {
        return state.isAsrReady() && initialized && state.getPhase() != EnginePhase.RESTARTING;
    }

    public boolean canProcessConversation() {
        if (!state.isAsrReady() || !state.isLlmReady() || !initialized || state.getPhase() == EnginePhase.RESTARTING) {
            return false;
        }
        if (state.isLlmReady() && !processManager.isLlmHealthy()) {
            state.setLlmReady(false);
            return false;
        }
        return true;
    }

    public boolean canPlayTts() {
        return state.isTtsReady() && initialized && state.getPhase() != EnginePhase.RESTARTING;
    }

    public boolean isLlmHealthy() {
        return processManager.isLlmHealthy();
    }

    public boolean isDownloadPaused() {
        return downloadPaused || asrModelService().isDownloadPaused() || ttsModelService().isDownloadPaused() || modelSetupService.isDownloadPaused();
    }

    public void pauseDownload() {
        downloadPaused = true;
        asrModelService().pauseDownload();
        ttsModelService().pauseDownload();
        modelSetupService.pauseDownload();
    }

    public void resumeDownload() {
        downloadPaused = false;
        asrModelService().resumeDownload();
        ttsModelService().resumeDownload();
        modelSetupService.resumeDownload();
    }

    public void cancelDownload() {
        downloadPaused = false;
        asrModelService().cancelDownload();
        ttsModelService().cancelDownload();
        modelSetupService.cancelDownload();
    }

    public void initWorkers() {
        if (initialized) return;

        if (!envSetupManager.isEnvironmentReady()) {
            env.info("环境未就绪，跳过 Worker 初始化");
            return;
        }

        runModuleLifecycle(false);
    }

    private void runModuleLifecycle(boolean rebuild) {
        try {
            env.info("开始初始化模块生命周期...");
            state.setPhase(EnginePhase.INITIALIZING);
            state.setLlmReady(false);

            if (rebuild || !initialized) {
                buildManagedModules();
                ModuleRegistrationContext registrationContext = new ModuleRegistrationContext(protocolRuntime, moduleServices);
                moduleHost.registerAll(registrationContext);
            }

            VoiceResourceSnapshot voiceResources = voiceResourceManager.materialize(protocolRuntime.voiceTriggers());
            ModuleRuntimeContext runtimeContext = new ModuleRuntimeContext(protocolRuntime, moduleServices, voiceResources, runtimeState);
            moduleHost.prepareAll(runtimeContext);
            moduleHost.startAll(runtimeContext);

            initialized = true;
            env.info("模块生命周期初始化完成");
        } catch (Exception e) {
            env.error("模块生命周期初始化失败", e);
            initialized = false;
            state.setPhase(EnginePhase.IDLE);
        }
    }

    private void refreshModuleLifecycle(boolean llmChanged) {
        if (!envSetupManager.isEnvironmentReady()) {
            env.info("环境未就绪，跳过模块生命周期刷新");
            return;
        }

        env.info("开始刷新模块生命周期，llmChanged=" + llmChanged);
        moduleHost.stopAll();
        moduleHost.destroyAll();
        moduleHost.unregisterAll(protocolRuntime);
        moduleHost.clear();
        if (llmChanged) {
            state.setLlmReady(false);
            processManager.stopLlmServer();
        }
        runtimeState.clearAsrEngine();
        state.setAsrReady(false);
        state.setTtsReady(false);
        runModuleLifecycle(true);
    }

    private void submitModuleLifecycleRefresh(boolean llmChanged, Runnable onComplete) {
        protocolRuntime.executors().submit(
                ProtocolTaskSpec.builder()
                        .moduleId("core.lifecycle")
                        .lane(ExecutionLane.MODEL_LOAD)
                        .concurrencyKey("core.lifecycle:refresh")
                        .maxConcurrency(1)
                        .queueCapacity(1)
                        .build(),
                () -> {
                    try {
                        refreshModuleLifecycle(llmChanged);
                    } finally {
                        if (onComplete != null) {
                            onComplete.run();
                        }
                    }
                }
        );
    }

    private void buildManagedModules() {
        moduleHost.clear();
        moduleServices.clear();

        llmEngineProvider = new LlmEngineProvider(env, config);

        moduleHost.registerModule(new IrModule(protocolRuntime, irCommandParser));
        moduleHost.registerModule(new LlmModule(llmEngineProvider.getLlmEngine(), protocolRuntime));
        moduleHost.registerModule(new TtsModule(audioBridge, eventBus, protocolRuntime, env, config, () -> interruptOngoingProcessing(), () -> modelManager));
        moduleHost.registerModule(new AsrModule(audioBridge, eventBus, protocolRuntime, env, config, this::canAcceptVoiceInput, this::interruptOngoingProcessing));
        moduleHost.registerModule(new UiProtocolBridge(protocolRuntime, eventBus));
        moduleHost.registerModule(new ChatAssistantModule(protocolRuntime));

        moduleServices.register(ModelSetupService.class, modelSetupService);
        moduleServices.register(LlmServerProcessManager.class, processManager);

        env.info("模块实例构建完成：module.ir/module.llm/module.tts/module.asr/module.ui/module.chat_assistant");
    }

    public void restartEngineAsync(boolean llmChanged, Runnable onComplete) {
        if (isRestarting) {
            env.warn("引擎正在重启，忽略重复请求");
            return;
        }
        isRestarting = true;
        state.setPhase(EnginePhase.RESTARTING);
        submitModuleLifecycleRefresh(llmChanged, () -> {
            isRestarting = false;
            state.refreshPhase();
            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    public long interruptOngoingProcessing() {
        env.info("打断正在进行的 LLM/TTS 处理");
        audioBridge.stopTtsPlayback();
        TtsWorker worker = moduleServices.find(TtsWorker.class).orElse(null);
        if (worker != null) worker.interruptSynthesis();
        return eventBus.interruptLlmAndTts();
    }

    public void speakAlert(String text) {
        ttsModelService().speakAlert(text, false);
    }

    public void speakAlertWithInterrupt(String text) {
        ttsModelService().speakAlert(text, true);
    }

    public AsrModelInfo resolveCurrentAsrModelInfo() {
        return asrModelService().resolveCurrentModelInfo();
    }

    public Path resolveAsrModelDir(AsrModelInfo info) {
        return asrModelService().resolveModelDir(info);
    }

    public boolean hasAsrModelContent(AsrModelInfo info) {
        return asrModelService().hasModelContent(info);
    }

    public void deleteAsrModel(AsrModelInfo info) {
        asrModelService().deleteModel(info);
    }

    public void downloadAsrModel(AsrModelInfo info, String githubProxyUrl, DownloadProgressCallback callback) {
        asrModelService().downloadModel(info, githubProxyUrl, new AsrModelService.DownloadProgressCallback() {
            @Override
            public void onProgress(String label, int percent) {
                callback.onProgress(label, percent);
            }

            @Override
            public void onComplete() {
                callback.onComplete();
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }
        });
    }

    public AsrModelService getAsrModelService() {
        return asrModelService();
    }

    public TtsModelInfo resolveCurrentTtsModelInfo() {
        return ttsModelService().resolveCurrentModelInfo();
    }

    public Path resolveCurrentTtsModelDir() {
        return ttsModelService().resolveCurrentModelDir();
    }

    public boolean hasTtsModelContent(TtsModelInfo info) {
        return ttsModelService().hasModelContent(info);
    }

    public void deleteTtsModel(TtsModelInfo info) {
        ttsModelService().deleteModel(info);
    }

    public void downloadTtsModel(TtsModelInfo info, String proxyUrl, DownloadProgressCallback callback) {
        ttsModelService().downloadModel(info, proxyUrl, new TtsModelService.DownloadProgressCallback() {
            @Override
            public void onProgress(String label, int percent) {
                callback.onProgress(label, percent);
            }

            @Override
            public void onComplete() {
                callback.onComplete();
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }
        });
    }

    public TtsModelService getTtsModelService() {
        return ttsModelService();
    }

    private TtsModelService ttsModelService() {
        return moduleServices.require(TtsModelService.class);
    }

    public void downloadPresetModels(VramTier tier, DownloadProgressCallback callback) {
        modelSetupService.downloadPresetModels(tier, new ModelSetupService.DownloadProgressCallback() {
            @Override
            public void onProgress(String label, int percent) {
                callback.onProgress(label, percent);
            }

            @Override
            public void onComplete() {
                callback.onComplete();
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }
        });
    }

    public ModelSetupService getModelSetupService() {
        return modelSetupService;
    }

    public void openVoiceLibraryFolder() {
        try {
            Path dir = config.getVoiceLibraryPath();
            Files.createDirectories(dir);
            env.openFolder(dir);
        } catch (Exception e) {
            env.error("打开音色库目录失败", e);
        }
    }

    public List<String> listVoiceSamples() {
        Path voiceDir = config.getVoiceLibraryPath();
        if (!Files.isDirectory(voiceDir)) {
            return Collections.emptyList();
        }
        try (var stream = Files.list(voiceDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> {
                        String lower = name.toLowerCase();
                        return lower.endsWith(".wav") || lower.endsWith(".mp3") || lower.endsWith(".flac");
                    })
                    .sorted(String::compareToIgnoreCase)
                    .toList();
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    public void previewAsr(PreviewAsrCallback callback) {
        asrModelService().preview(new AsrModelService.PreviewCallback() {
            @Override
            public void onReady() {
                callback.onReady();
            }

            @Override
            public void onResult(String text) {
                callback.onResult(text);
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }

            @Override
            public void onFinish() {
                callback.onFinish();
            }
        });
    }

    public void previewTts(String text, float speed, TtsModelInfo info, PreviewTtsCallback callback) {
        ttsModelService().preview(text, speed, info, new TtsModelService.PreviewCallback() {
            @Override
            public void onReady() {
                callback.onReady();
            }

            @Override
            public void onPlaying() {
                callback.onPlaying();
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }

            @Override
            public void onFinish() {
                callback.onFinish();
            }
        });
    }

    public boolean isPreviewRunning() {
        return asrModelService().isPreviewRunning() || ttsModelService().isPreviewRunning();
    }

    public void stopPreview() {
        asrModelService().stopPreview();
        ttsModelService().stopPreview();
    }

    public void destroy() {
        env.info("核心管理器：销毁全部资源");

        long stoppedSession = eventBus.beginNewSession();
        eventBus.clearAllQueues();
        eventBus.publishEvent(new InterruptEvent(stoppedSession));

        moduleHost.destroyAll();
        moduleHost.unregisterAll(protocolRuntime);
        moduleHost.clear();

        processManager.stopServices();
        runtimeState.clearAsrEngine();

        eventBus.clearAllQueues();
        state.reset();
        state.setPhase(EnginePhase.DESTROYED);
        initialized = false;
    }

    public void onEnvSetupFinished() {
        env.info("环境配置完成，刷新模块生命周期");
        reloadNatives();
        if (initialized) {
            submitModuleLifecycleRefresh(true, null);
        } else {
            initWorkers();
        }
    }

    public void onModelDownloadFinished() {
        env.info("模型下载完成，刷新模块生命周期");
        if (initialized) {
            submitModuleLifecycleRefresh(true, null);
        } else {
            initWorkers();
        }
    }

    public void reloadNatives() {
        try {
            if (!nativeLibBridge.isNativesReady()) {
                nativeLibBridge.extractAndLoadAll();
            }
        } catch (Exception e) {
            env.error("重新加载 Native 库失败", e);
        }
    }
}
