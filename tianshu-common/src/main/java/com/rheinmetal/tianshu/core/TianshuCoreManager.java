package com.rheinmetal.tianshu.core;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.INativeLibBridge;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.constant.ModelPresets;
import com.rheinmetal.tianshu.constant.VramTier;
import com.rheinmetal.tianshu.core.Engine.AsrEngine;
import com.rheinmetal.tianshu.core.Engine.TtsEngine;
import com.rheinmetal.tianshu.event.InterruptEvent;
import com.rheinmetal.tianshu.event.TianshuEventBus;
import com.rheinmetal.tianshu.model.AsrModelDownloader;
import com.rheinmetal.tianshu.model.AsrModelInfo;
import com.rheinmetal.tianshu.model.AsrModelManager;
import com.rheinmetal.tianshu.model.HuggingFaceDownloader;
import com.rheinmetal.tianshu.model.ModelFilesMissingException;
import com.rheinmetal.tianshu.model.ModelManager;
import com.rheinmetal.tianshu.model.ModelSettings;
import com.rheinmetal.tianshu.model.TtsModelInfo;
import com.rheinmetal.tianshu.utils.PathUtils;
import com.rheinmetal.tianshu.worker.AsrWorker;
import com.rheinmetal.tianshu.worker.LlmWorker;
import com.rheinmetal.tianshu.worker.TtsWorker;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

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

        void setAsrReady(boolean v) {
            asrReady = v;
            refreshPhase();
        }
        void setLlmReady(boolean v) {
            llmReady = v;
            refreshPhase();
        }
        void setTtsReady(boolean v) {
            ttsReady = v;
            refreshPhase();
        }

        void setPhase(EnginePhase p) { phase = p; }

        void reset() {
            asrReady = false;
            llmReady = false;
            ttsReady = false;
            phase = EnginePhase.IDLE;
        }

        private void refreshPhase() {
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
    private final State state;
    private final EnvSetupManager envSetupManager;
    private final ProcessManager processManager;
    private final TianshuThreadPool threadPool;
    private final ModelManager modelManager;
    private final AsrModelDownloader asrModelDownloader;

    private AsrEngine asrEngine;
    private AsrWorker asrWorker;
    private LlmWorker llmWorker;
    private TtsWorker ttsWorker;

    private volatile boolean initialized = false;
    private volatile boolean downloadCancelled = false;
    private volatile boolean downloadPaused = false;
    private volatile boolean isRestarting = false;

    public TianshuCoreManager(IGameEnvironment env, ITianshuConfig config, INativeLibBridge nativeLibBridge, IAudioBridge audioBridge) {
        this.env = env;
        this.config = config;
        this.nativeLibBridge = nativeLibBridge;
        this.audioBridge = audioBridge;
        this.eventBus = new TianshuEventBus(env);
        this.state = new State();
        this.envSetupManager = new EnvSetupManager(env, nativeLibBridge);
        this.processManager = new ProcessManager(env, config, nativeLibBridge, () -> {
            state.setLlmReady(true);
            env.executeOnMainThread(() -> env.displayMessageToPlayer("§b[天极] §f中枢核心已就绪"));
        });
        this.threadPool = new TianshuThreadPool(env);
        this.modelManager = new ModelManager(config);
        this.asrModelDownloader = new AsrModelDownloader(env);
    }

    public State getState() {
        return state;
    }

    public TianshuEventBus getEventBus() {
        return eventBus;
    }

    public EnvSetupManager getEnvSetupManager() {
        return envSetupManager;
    }

    public ProcessManager getProcessManager() {
        return processManager;
    }

    public ModelManager getModelManager() {
        return modelManager;
    }

    public AsrEngine getAsrEngine() {
        return asrEngine;
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
        return downloadPaused || asrModelDownloader.isDownloadPaused();
    }

    public void pauseDownload() {
        downloadPaused = true;
        asrModelDownloader.pauseDownload();
    }

    public void resumeDownload() {
        downloadPaused = false;
        asrModelDownloader.resumeDownload();
    }

    public void cancelDownload() {
        downloadCancelled = true;
        downloadPaused = false;
        asrModelDownloader.cancelDownload();
    }

    public void tryInitEngine() {
        if (state.isAsrReady()) {
            env.info("ASR 引擎已就绪，跳过初始化");
            return;
        }

        if (!envSetupManager.isEnvironmentReady()) {
            env.info("环境未就绪，静默等待");
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

            asrEngine = new AsrEngine(env);

            if (modelInfo != null) {
                Path modelDir = originalModelPath.resolve(modelInfo.name);
                if (!Files.isDirectory(modelDir)) {
                    modelDir = originalModelPath;
                }
                File safeDir = PathUtils.getSafeModelDir(modelDir.toFile());
                if (safeDir == null) {
                    env.error("获取安全模型目录失败", null);
                    return;
                }
                try {
                    if (!asrEngine.initialize(modelInfo, safeDir.toPath())) {
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
                if (!asrEngine.initialize(safeDir.getAbsolutePath())) {
                    env.error("ASR 引擎初始化失败", null);
                    return;
                }
            }

            state.setAsrReady(true);
            env.info("ASR 引擎初始化成功");
            env.executeOnMainThread(() -> env.displayMessageToPlayer("§b[天极] §f态势感知已就绪"));
        } catch (Throwable t) {
            env.error("ASR 引擎初始化失败", t);
        }
    }

    public void initWorkers() {
        if (initialized) return;

        if (!envSetupManager.isEnvironmentReady()) {
            env.info("环境未就绪，跳过 Worker 初始化");
            return;
        }

        try {
            env.info("开始初始化 Workers...");
            state.setPhase(EnginePhase.INITIALIZING);
            state.setLlmReady(false);

            asrWorker = new AsrWorker(audioBridge, this, env, config);
            llmWorker = new LlmWorker(this, env, config);
            ttsWorker = new TtsWorker(audioBridge, this, env, config);

            threadPool.getAsrWorker().execute(asrWorker);
            threadPool.getLlmWorker().execute(llmWorker);
            threadPool.getTtsWorker().execute(ttsWorker);

            ttsWorker.initEngine();
            if (ttsWorker.isEngineInitialized()) {
                state.setTtsReady(true);
                env.executeOnMainThread(() -> env.displayMessageToPlayer("§b[天极] §f灵音共鸣已就绪"));
            }

            new Thread(() -> processManager.startLlmServer(), "LLM-Process-Starter").start();

            initialized = true;
            env.info("Workers 初始化完成");
        } catch (Exception e) {
            env.error("Workers 初始化失败", e);
            initialized = false;
            state.setPhase(EnginePhase.IDLE);
        }
    }

    public void restartEngineAsync(boolean llmChanged, Runnable onComplete) {
        if (isRestarting) {
            env.warn("引擎正在重启，忽略重复请求");
            return;
        }
        isRestarting = true;
        state.setPhase(EnginePhase.RESTARTING);
        new Thread(() -> {
            try {
                env.info("开始异步重启引擎，llmChanged=" + llmChanged);
                long restartSession = eventBus.beginNewSession();
                eventBus.clearAllQueues();
                eventBus.publishEvent(new InterruptEvent(restartSession));
                audioBridge.stopRecording();
                audioBridge.stopStreamRecording();
                audioBridge.stopTtsPlayback();
                if (ttsWorker != null) {
                    ttsWorker.interruptSynthesis();
                }

                if (llmChanged) {
                    state.setLlmReady(false);
                    processManager.stopService(ProcessManager.ServiceType.LLM);
                    processManager.startLlmServer();
                }

                state.setTtsReady(false);
                if (ttsWorker != null) {
                    ttsWorker.shutdownEngine();
                    ttsWorker.initEngine();
                    if (ttsWorker.isEngineInitialized()) {
                        state.setTtsReady(true);
                    }
                }

                if (!llmChanged && !state.isLlmReady()) {
                    env.info("LLM 未就绪且未变更，尝试重新启动 LLM 服务");
                    processManager.startLlmServer();
                }
            } catch (Exception e) {
                env.error("异步重启引擎失败", e);
            } finally {
                isRestarting = false;
                state.refreshPhase();
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        }, "Tianshu-Restart-Engine").start();
    }

    public long interruptOngoingProcessing() {
        env.info("打断正在进行的 LLM/TTS 处理");
        audioBridge.stopTtsPlayback();
        if (ttsWorker != null) ttsWorker.interruptSynthesis();
        return eventBus.interruptLlmAndTts();
    }

    public AsrModelInfo resolveCurrentAsrModelInfo() {
        Path modelPath = config.getAsrModelPath();
        if (modelPath == null || modelPath.getFileName() == null) {
            return null;
        }
        String dirName = modelPath.getFileName().toString();
        AsrModelInfo info = AsrModelManager.getModelByName(dirName);
        if (info != null) return info;
        return AsrModelManager.getModelById(dirName);
    }

    public Path resolveAsrModelDir(AsrModelInfo info) {
        if (info == null || info.name == null) return null;
        return config.getAsrBasePath().resolve(info.name);
    }

    public boolean hasAsrModelContent(AsrModelInfo info) {
        Path modelDir = resolveAsrModelDir(info);
        if (modelDir == null || !Files.exists(modelDir)) return false;
        return AsrModelManager.isModelDownloaded(info, config.getAsrBasePath());
    }

    public void deleteAsrModel(AsrModelInfo info) {
        Path modelDir = resolveAsrModelDir(info);
        if (modelDir == null || !Files.exists(modelDir)) return;
        try {
            deleteRecursively(modelDir);
        } catch (IOException e) {
            env.error("删除 ASR 模型失败", e);
        }
    }

    public void downloadAsrModel(AsrModelInfo info, String githubProxyUrl, DownloadProgressCallback callback) {
        if (info == null) {
            callback.onError("ASR 模型信息为空");
            return;
        }
        asrModelDownloader.download(info, resolveAsrModelDir(info), githubProxyUrl, new AsrModelDownloader.DownloadProgressCallback() {
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

    public AsrModelDownloader getAsrModelDownloader() {
        return asrModelDownloader;
    }

    public TtsModelInfo resolveCurrentTtsModelInfo() {
        Path modelPath = config.getTtsModelPath();
        if (modelPath == null || modelPath.getFileName() == null) {
            return null;
        }
        String dirName = modelPath.getFileName().toString();
        List<TtsModelInfo> matchedZipVoice = new ArrayList<>();
        for (TtsModelInfo info : ModelManager.loadTtsModelCatalog()) {
            if (info == null || info.name == null) {
                continue;
            }
            if (info.name.equalsIgnoreCase(dirName) || modelPath.endsWith(info.name)) {
                return info;
            }
            if ("zipvoice".equals(info.getEngineType())) {
                matchedZipVoice.add(info);
            }
        }
        if ("ZipVoice".equalsIgnoreCase(dirName)) {
            for (TtsModelInfo info : matchedZipVoice) {
                if ("ZipVoice-int8".equalsIgnoreCase(info.name)) {
                    return info;
                }
            }
            return matchedZipVoice.isEmpty() ? null : matchedZipVoice.get(0);
        }
        return null;
    }

    public Path resolveCurrentTtsModelDir() {
        TtsModelInfo info = resolveCurrentTtsModelInfo();
        if (info != null) {
            return resolveTtsModelDir(info);
        }
        return config.getTtsModelPath();
    }

    public boolean hasTtsModelContent(TtsModelInfo info) {
        Path modelDir = resolveTtsModelDir(info);
        if (modelDir == null || !Files.exists(modelDir)) {
            return false;
        }
        try (var stream = Files.list(modelDir)) {
            return stream.findAny().isPresent();
        } catch (IOException e) {
            return false;
        }
    }

    public void deleteTtsModel(TtsModelInfo info) {
        Path modelDir = resolveTtsModelDir(info);
        if (modelDir == null || !Files.exists(modelDir)) {
            return;
        }
        try {
            deleteRecursively(modelDir);
        } catch (IOException e) {
            env.error("删除 TTS 模型失败", e);
        }
    }

    public void downloadTtsModel(TtsModelInfo info, String proxyUrl, DownloadProgressCallback callback) {
        if (info == null) {
            callback.onError("TTS 模型信息为空");
            return;
        }
        downloadCancelled = false;
        downloadPaused = false;
        Thread.ofVirtual().start(() -> {
            try {
                Path modelDir = resolveTtsModelDir(info);
                if (modelDir == null) {
                    callback.onError("无法解析模型目录");
                    return;
                }
                if (info.downloadUrl != null && !info.downloadUrl.isBlank()) {
                    downloadArchiveTtsModel(info, modelDir, proxyUrl, callback);
                } else if ("moss".equals(info.getEngineType())) {
                    downloadMossModel(modelDir, callback);
                } else {
                    downloadSherpaModel(info, modelDir, callback);
                }
                ModelSettings.saveTtsSettings(modelDir, ModelSettings.loadTtsSettings(modelDir));
                callback.onProgress("完成", 100);
                callback.onComplete();
            } catch (Exception e) {
                callback.onError(e.getMessage() != null ? e.getMessage() : "下载失败");
            }
        });
    }

    public void downloadPresetModels(VramTier tier, DownloadProgressCallback callback) {
        if (tier == VramTier.CUSTOM) {
            callback.onError("自定义预设不支持一键下载");
            return;
        }
        downloadCancelled = false;
        downloadPaused = false;
        Thread.ofVirtual().start(() -> {
            try {
                callback.onProgress("ASR:", 5);
                AsrModelInfo asrModel = AsrModelManager.getDefaultModel(tier);
                if (asrModel != null) {
                    Path asrDir = config.getAsrBasePath().resolve(asrModel.name);
                    asrModelDownloader.downloadSync(asrModel, asrDir, null, new AsrModelDownloader.DownloadProgressCallback() {
                        @Override public void onProgress(String label, int percent) { callback.onProgress("ASR:", percent); }
                        @Override public void onComplete() {}
                        @Override public void onError(String message) { throw new RuntimeException("ASR 下载失败: " + message); }
                    });
                } else {
                    HuggingFaceDownloader downloader = new HuggingFaceDownloader(env);
                    Path asrDir = config.getAsrBasePath().resolve(ModelPresets.getPresetAsrName(tier));
                    downloader.downloadModelFiles(ModelPresets.getPresetAsrName(tier).equals("ParaformerOnnx")
                            ? "csukuangfj/sherpa-onnx-paraformer-zh-2023-09-14"
                            : "csukuangfj/sherpa-onnx-zipformer-multi-zh-hans-2023-10-24",
                            asrDir, "main", true, 3);
                }
                callback.onProgress("ASR:", 100);

                callback.onProgress("LLM:", 5);
                HuggingFaceDownloader downloader = new HuggingFaceDownloader(env);
                Path llmDir = config.getLlmBasePath().resolve(ModelPresets.getPresetLlmName(tier));
                downloader.downloadModelFiles(ModelPresets.getPresetTtsModelId(tier).startsWith("OpenMOSS")
                        ? "csukuangfj/sherpa-onnx-vits-zh-hf-keqing"
                        : ModelPresets.getPresetTtsModelId(tier),
                        llmDir, "main", true, 3);
                callback.onProgress("LLM:", 100);

                callback.onProgress("TTS:", 5);
                String ttsName = ModelPresets.getPresetTtsName(tier);
                Path ttsDir = config.getTtsBasePath().resolve(ttsName);
                if (ttsName.contains("MOSS")) {
                    downloader.downloadModelFiles("OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX", ttsDir, "main", true, 3);
                    downloader.downloadModelFiles("OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX", ttsDir, "main", true, 3);
                } else {
                    downloader.downloadModelFiles(ModelPresets.getPresetTtsModelId(tier), ttsDir, "main", true, 3);
                }
                callback.onProgress("TTS:", 100);

                callback.onComplete();
            } catch (Exception e) {
                callback.onError(e.getMessage() != null ? e.getMessage() : "预设模型下载失败");
            }
        });
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
        callback.onError("ASR 试听暂不可用");
        callback.onFinish();
    }

    public void previewTts(String text, float speed, TtsModelInfo info, PreviewTtsCallback callback) {
        callback.onError("TTS 试听暂不可用");
        callback.onFinish();
    }

    public void destroy() {
        env.info("核心管理器：销毁全部资源");

        long stoppedSession = eventBus.beginNewSession();
        eventBus.clearAllQueues();
        eventBus.publishEvent(new InterruptEvent(stoppedSession));

        if (asrWorker != null) asrWorker.stop();
        if (llmWorker != null) llmWorker.stop();
        if (ttsWorker != null) ttsWorker.stop();

        processManager.stopServices();

        if (asrEngine != null) {
            asrEngine.shutdown();
            asrEngine = null;
        }

        eventBus.clearAllQueues();
        state.reset();
        state.setPhase(EnginePhase.DESTROYED);
        initialized = false;
    }

    public void onEnvSetupFinished() {
        env.info("环境配置完成，尝试初始化引擎");
        reloadNatives();
        tryInitEngine();
        initWorkers();
    }

    public void onModelDownloadFinished() {
        env.info("模型下载完成，尝试初始化引擎");
        tryInitEngine();
        initWorkers();
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

    private Path resolveTtsModelDir(TtsModelInfo info) {
        if (info == null || info.name == null) {
            return null;
        }
        String modelDirName = "zipvoice".equals(info.getEngineType()) ? "ZipVoice" : info.name;
        return config.getTtsBasePath().resolve(modelDirName);
    }

    private void downloadSherpaModel(TtsModelInfo info, Path modelDir, DownloadProgressCallback callback) throws Exception {
        callback.onProgress("解析 HuggingFace 文件", 5);
        HuggingFaceDownloader downloader = new HuggingFaceDownloader(env);
        downloader.downloadModelFiles(info.id, modelDir, "main", true, 3);
        callback.onProgress("下载完成", 95);
    }

    private void downloadMossModel(Path modelDir, DownloadProgressCallback callback) throws Exception {
        callback.onProgress("下载 MOSS 模型", 5);
        HuggingFaceDownloader downloader = new HuggingFaceDownloader(env);
        downloader.downloadModelFiles("OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX", modelDir, "main", true, 3);
        downloader.downloadModelFiles("OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX", modelDir, "main", true, 3);
        callback.onProgress("下载完成", 95);
    }

    private void downloadArchiveTtsModel(TtsModelInfo info, Path modelDir, String proxyUrl, DownloadProgressCallback callback) throws Exception {
        Files.createDirectories(modelDir.getParent());
        String archiveName = archiveName(info.downloadUrl);
        Path archivePath = modelDir.getParent().resolve(archiveName);
        String finalUrl = buildDownloadUrl(info.downloadUrl, proxyUrl);

        callback.onProgress("下载压缩包", 5);
        downloadFile(finalUrl, archivePath, 5, 60_000, (downloaded, total) -> {
            int percent = total > 0 ? Math.min(85, (int) (downloaded * 80 / total) + 5) : 40;
            callback.onProgress("下载压缩包", percent);
        });

        callback.onProgress("解压模型", 90);
        Path tempDir = modelDir.getParent().resolve(modelDir.getFileName().toString() + "-extract");
        deleteRecursivelyIfExists(tempDir);
        Files.createDirectories(tempDir);
        extractTarBz2(archivePath, tempDir);

        Path extractedModelDir = resolveExtractedModelDir(tempDir);
        deleteRecursivelyIfExists(modelDir);
        Files.move(extractedModelDir, modelDir, StandardCopyOption.REPLACE_EXISTING);
        deleteRecursivelyIfExists(tempDir);
        Files.deleteIfExists(archivePath);
        callback.onProgress("解压完成", 95);
    }

    private Path resolveExtractedModelDir(Path extractedRoot) throws IOException {
        try (var stream = Files.list(extractedRoot)) {
            List<Path> entries = stream.toList();
            if (entries.size() == 1 && Files.isDirectory(entries.get(0))) {
                return entries.get(0);
            }
        }
        return extractedRoot;
    }

    private String buildDownloadUrl(String downloadUrl, String proxyUrl) {
        if (proxyUrl == null || proxyUrl.isBlank()) {
            return downloadUrl;
        }
        String normalizedProxy = proxyUrl.endsWith("/") ? proxyUrl : proxyUrl + "/";
        if (downloadUrl.startsWith(normalizedProxy)) {
            return downloadUrl;
        }
        return normalizedProxy + downloadUrl;
    }

    private String archiveName(String downloadUrl) {
        int idx = downloadUrl.lastIndexOf('/');
        return idx >= 0 ? downloadUrl.substring(idx + 1) : "model.tar.bz2";
    }

    private void extractTarBz2(Path archive, Path targetDir) throws IOException {
        try (InputStream fis = Files.newInputStream(archive);
             InputStream bis = new java.io.BufferedInputStream(fis);
             BZip2CompressorInputStream bzIn = new BZip2CompressorInputStream(bis);
             TarArchiveInputStream tarIn = new TarArchiveInputStream(bzIn)) {
            TarArchiveEntry entry;
            while ((entry = tarIn.getNextEntry()) != null) {
                Path outputPath = targetDir.resolve(entry.getName()).normalize();
                if (!outputPath.startsWith(targetDir.normalize())) {
                    throw new IOException("非法压缩包路径: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(outputPath);
                    continue;
                }
                Files.createDirectories(outputPath.getParent());
                try (OutputStream out = Files.newOutputStream(outputPath)) {
                    tarIn.transferTo(out);
                }
            }
        }
    }

    private interface ProgressListener {
        void onProgress(long downloaded, long total);
    }

    private void downloadFile(String urlString, Path targetPath, int maxRetries, int timeoutMillis, ProgressListener listener) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            waitIfDownloadPaused();
            HttpURLConnection conn = null;
            Path tempPath = targetPath.resolveSibling(targetPath.getFileName() + ".downloading");
            try {
                Files.createDirectories(targetPath.getParent());
                conn = (HttpURLConnection) new URL(urlString).openConnection();
                conn.setConnectTimeout(timeoutMillis);
                conn.setReadTimeout(timeoutMillis);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "Tianshu-Downloader/1.0");
                int code = conn.getResponseCode();
                if (code != 200) {
                    throw new IOException("HTTP 错误: " + code);
                }
                long total = conn.getContentLengthLong();
                long downloaded = 0L;
                try (InputStream in = conn.getInputStream();
                     OutputStream out = Files.newOutputStream(tempPath)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        if (downloadCancelled) {
                            throw new IOException("下载已取消");
                        }
                        waitIfDownloadPaused();
                        out.write(buffer, 0, read);
                        downloaded += read;
                        listener.onProgress(downloaded, total);
                    }
                }
                Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException e) {
                last = e;
                Files.deleteIfExists(tempPath);
                if (downloadCancelled) {
                    throw e;
                }
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        throw last != null ? last : new IOException("下载失败");
    }

    private void waitIfDownloadPaused() throws IOException {
        while (downloadPaused) {
            if (downloadCancelled) {
                throw new IOException("下载已取消");
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("下载线程被中断", e);
            }
        }
        if (downloadCancelled) {
            throw new IOException("下载已取消");
        }
    }

    private void deleteRecursivelyIfExists(Path path) throws IOException {
        if (Files.exists(path)) {
            deleteRecursively(path);
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw e;
        }
    }
}
