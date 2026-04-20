package com.rheinmetal.tianshu.core;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.INativeLibBridge;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.constant.ModelPresets;
import com.rheinmetal.tianshu.constant.ModelUrls;
import com.rheinmetal.tianshu.constant.VramTier;
import com.rheinmetal.tianshu.core.Engine.AsrEngine;
import com.rheinmetal.tianshu.core.Engine.TtsEngine;
import com.rheinmetal.tianshu.event.TianshuEventBus;
import com.rheinmetal.tianshu.model.HuggingFaceDownloader;
import com.rheinmetal.tianshu.model.ModelDownloader;
import com.rheinmetal.tianshu.model.ModelManager;
import com.rheinmetal.tianshu.model.TtsModelInfo;
import com.rheinmetal.tianshu.utils.PathUtils;
import com.rheinmetal.tianshu.worker.AsrWorker;
import com.rheinmetal.tianshu.worker.LlmWorker;
import com.rheinmetal.tianshu.worker.TtsWorker;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class TianshuCoreManager {

    public static class State {
        private volatile boolean asrReady = false;
        private volatile boolean llmReady = false;
        private volatile boolean ttsReady = false;

        public boolean isAsrReady() { return asrReady; }
        public boolean isLlmReady() { return llmReady; }
        public boolean isTtsReady() { return ttsReady; }

        void setAsrReady(boolean v) { asrReady = v; }
        void setLlmReady(boolean v) { llmReady = v; }
        void setTtsReady(boolean v) { ttsReady = v; }

        void reset() {
            asrReady = false;
            llmReady = false;
            ttsReady = false;
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

    private AsrEngine asrEngine;
    private AsrWorker asrWorker;
    private LlmWorker llmWorker;
    private TtsWorker ttsWorker;

    private volatile boolean initialized = false;

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
            env.displayMessageToPlayer("\u00a7b[\u5929\u6781] \u00a7f\u4e2d\u67a2\u6838\u5fc3\u5df2\u5c31\u7eea");
        });
        this.threadPool = new TianshuThreadPool(env);
        this.modelManager = new ModelManager(config);
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
        return state.isAsrReady() && state.isLlmReady() && state.isTtsReady();
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void tryInitEngine() {
        if (state.isAsrReady()) {
            env.info("引擎已就绪，跳过初始化");
            return;
        }

        if (!envSetupManager.isEnvironmentReady()) {
            env.info("环境未就绪，静默等待");
            return;
        }

        try {
            String originalModelPath = config.getAsrModelPath().toString();
            File originalDir = new File(originalModelPath);

            if (!originalDir.exists() || !originalDir.isDirectory()) {
                env.info("模型目录不存在，静默等待");
                return;
            }

            File tokensFile = new File(originalDir, "tokens.txt");
            if (!tokensFile.exists() || !tokensFile.isFile()) {
                env.info("模型文件不完整，静默等待");
                return;
            }

            File safeDir = PathUtils.getSafeModelDir(originalDir);
            if (safeDir == null) {
                env.error("获取安全模型目录失败", null);
                return;
            }

            asrEngine = new AsrEngine(env);
            asrEngine.initialize(safeDir.getAbsolutePath());

            state.setAsrReady(true);
            env.info("引擎初始化成功");
            env.displayMessageToPlayer("\u00a7b[\u5929\u6781] \u00a7f\u6001\u52bf\u611f\u77e5\u5df2\u5c31\u7eea");
        } catch (Throwable t) {
            env.error("引擎初始化失败", t);
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

            asrWorker = new AsrWorker(audioBridge, this, env, config);
            llmWorker = new LlmWorker(this, env, config);
            ttsWorker = new TtsWorker(audioBridge, this, env, config);

            threadPool.getAsrWorker().execute(asrWorker);
            threadPool.getLlmWorker().execute(llmWorker);
            threadPool.getTtsWorker().execute(ttsWorker);

            ttsWorker.initEngine();
            if (ttsWorker.isEngineInitialized()) {
                state.setTtsReady(true);
                env.displayMessageToPlayer("\u00a7b[\u5929\u6781] \u00a7f\u7075\u97f3\u5171\u9e23\u5df2\u5c31\u7eea");
            }

            new Thread(() -> processManager.startLlmServer(), "LLM-Process-Starter").start();

            initialized = true;
            env.info("Workers 初始化完成");
        } catch (Exception e) {
            env.error("Workers 初始化失败", e);
            initialized = false;
        }
    }

    public void destroy() {
        env.info("核心管理器：销毁全部资源");

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
        initialized = false;
    }

    public void onEnvSetupFinished() {
        env.info("环境配置完成，尝试初始化引擎");
        reloadNatives();
        tryInitEngine();
    }

    public void onModelDownloadFinished() {
        env.info("模型下载完成，尝试初始化引擎");
        tryInitEngine();
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

    public interface PreviewAsrCallback {
        void onReady();
        void onResult(String text);
        void onError(String message);
        void onFinish();
    }

    public void previewAsr(PreviewAsrCallback callback) {
        new Thread(() -> {
            eventBus.publishEvent(new com.rheinmetal.tianshu.event.StopStreamRecordingEvent());

            AsrEngine previewEngine = new AsrEngine(env);
            try {
                Thread.sleep(300);
                Path modelPath = config.getAsrModelPath();
                previewEngine.initialize(modelPath.toString());
                callback.onReady();
                audioBridge.startRecording();
                Thread.sleep(5000);
                byte[] audioData = audioBridge.stopRecording();
                if (audioData == null || audioData.length == 0) {
                    callback.onError("未录到有效音频");
                    return;
                }
                String result = previewEngine.recognizeComplete(audioData);
                callback.onResult((result == null || result.isBlank()) ? "未识别到内容" : result);
            } catch (Throwable t) {
                env.error("ASR 试听失败", t);
                try { audioBridge.stopRecording(); } catch (Throwable ignored) {}
                callback.onError("试听失败，请检查模型与麦克风");
            } finally {
                try { previewEngine.shutdown(); } catch (Throwable ignored) {}
                callback.onFinish();
            }
        }, "Tianshu-ASR-Preview").start();
    }

    public interface PreviewTtsCallback {
        void onReady();
        void onPlaying();
        void onError(String message);
        void onFinish();
    }

    public void previewTts(String text, float speed, TtsModelInfo info, PreviewTtsCallback callback) {
        new Thread(() -> {
            TtsEngine previewEngine = new TtsEngine(env, config);
            try {
                Path modelPath = config.getTtsModelPath();
                TtsModelInfo resolvedInfo = info != null ? info : resolveCurrentTtsModelInfo();
                if (resolvedInfo != null) {
                    String vocoderPath = resolveVocoderPath(modelPath);
                    if (resolvedInfo.needVocoder && vocoderPath != null) {
                        previewEngine.initialize(modelPath.toString(), resolvedInfo, vocoderPath);
                    } else {
                        previewEngine.initialize(modelPath.toString(), resolvedInfo);
                    }
                } else {
                    previewEngine.initialize(modelPath.toString());
                }
                if (!previewEngine.isInitialized()) {
                    callback.onError("模型初始化失败");
                    return;
                }
                callback.onReady();
                previewEngine.setSpeed(speed);
                audioBridge.startTtsPlayback(previewEngine.getSampleRate());
                Thread.sleep(120);
                previewEngine.synthesizeSpeech(text, audioBridge::feedTtsAudio);
                audioBridge.stopTtsPlayback();
                callback.onPlaying();
            } catch (Throwable t) {
                env.error("TTS 试听失败", t);
                try { audioBridge.stopTtsPlayback(); } catch (Throwable ignored) {}
                callback.onError("试听失败，请检查模型与音频输出");
            } finally {
                try { previewEngine.shutdown(); } catch (Throwable ignored) {}
                callback.onFinish();
            }
        }, "Tianshu-TTS-Preview").start();
    }

    public interface DownloadProgressCallback {
        void onProgress(String label, int percent);
        void onComplete();
        void onError(String message);
    }

    public void downloadPresetModels(VramTier tier, DownloadProgressCallback callback) {
        List<String> asrUrls = switch (tier) {
            case LIGHT -> ModelUrls.ASR_LIGHT_URLS;
            case STANDARD -> ModelUrls.ASR_STANDARD_URLS;
            case DELUXE -> ModelUrls.ASR_DELUXE_URLS;
            default -> ModelUrls.ASR_STANDARD_URLS;
        };
        String llmUrl = switch (tier) {
            case LIGHT -> ModelUrls.LLM_LIGHT_URL;
            case STANDARD -> ModelUrls.LLM_STANDARD_URL;
            case DELUXE -> ModelUrls.LLM_DELUXE_URL;
            default -> ModelUrls.LLM_STANDARD_URL;
        };

        if (asrUrls.stream().anyMatch(u -> u.contains("example.com"))
                || llmUrl.contains("example.com")
                || asrUrls.isEmpty() || llmUrl.isEmpty()) {
            callback.onError("模型下载链接未配置");
            return;
        }

        String ttsModelId = ModelPresets.getPresetTtsModelId(tier);
        TtsModelInfo ttsInfo = findTtsModelById(ttsModelId);
        if (ttsInfo == null) {
            callback.onError("未找到预设TTS模型: " + ttsModelId);
            return;
        }

        LinkedList<Map.Entry<String, Path>> queue = new LinkedList<>();
        Path asrDir = config.getAsrModelPath();
        for (String url : asrUrls) addDownloadTask(queue, url, asrDir);
        addDownloadTask(queue, llmUrl, config.getLlmModelPath());

        if (!queue.isEmpty()) {
            DownloadProgressCallback ttsCallback = new DownloadProgressCallback() {
                @Override public void onProgress(String label, int percent) { callback.onProgress(label, percent); }
                @Override public void onError(String message) { callback.onError(message); }
                @Override public void onComplete() { callback.onComplete(); }
            };
            executeDownloadQueue(queue, new DownloadProgressCallback() {
                @Override public void onProgress(String label, int percent) { callback.onProgress(label, percent); }
                @Override public void onError(String message) { callback.onError(message); }
                @Override public void onComplete() {
                    downloadTtsModel(ttsInfo, ttsCallback);
                }
            });
        } else {
            downloadTtsModel(ttsInfo, callback);
        }
    }

    private TtsModelInfo findTtsModelById(String modelId) {
        List<TtsModelInfo> catalog = ModelManager.loadTtsModelCatalog();
        for (TtsModelInfo info : catalog) {
            if (modelId.equals(info.id)) return info;
        }
        return null;
    }

    private void addDownloadTask(LinkedList<Map.Entry<String, Path>> queue, String url, Path baseDir) {
        try {
            String fileName = new java.net.URI(url).getPath().substring(new java.net.URI(url).getPath().lastIndexOf('/') + 1);
            Path filePath = baseDir.resolve(fileName);
            if (!Files.exists(filePath)) {
                queue.add(Map.entry(url, filePath));
            }
        } catch (Exception e) {
            // skip invalid url
        }
    }

    private void executeDownloadQueue(LinkedList<Map.Entry<String, Path>> queue, DownloadProgressCallback callback) {
        if (queue.isEmpty()) {
            callback.onComplete();
            return;
        }
        Map.Entry<String, Path> task = queue.poll();
        String url = task.getKey();
        Path filePath = task.getValue();

        String label;
        if (filePath.startsWith(config.getAsrModelPath())) {
            label = "ASR:";
        } else if (filePath.startsWith(config.getLlmModelPath())) {
            label = "LLM:";
        } else {
            label = "TTS:";
        }

        ModelDownloader.downloadAsync(url, filePath, new ModelDownloader.DownloadCallback() {
            @Override
            public void onProgress(long downloadedBytes, long totalBytes) {
                if (totalBytes > 0) {
                    int progress = (int) Math.round((double) downloadedBytes * 100 / totalBytes);
                    callback.onProgress(label, progress);
                }
            }

            @Override
            public void onSuccess(Path p) {
                executeDownloadQueue(queue, callback);
            }

            @Override
            public void onError(String m) {
                queue.clear();
                callback.onError(m);
            }
        });
    }

    public void restartEngineAsync(Runnable onDone) {
        restartEngineAsync(true, onDone);
    }

    public void restartEngineAsync(boolean restartLlm, Runnable onDone) {
        new Thread(() -> {
            if (asrWorker != null) asrWorker.stop();
            if (ttsWorker != null) ttsWorker.stop();
            if (asrEngine != null) {
                asrEngine.shutdown();
                asrEngine = null;
            }
            state.setAsrReady(false);
            state.setTtsReady(false);
            initialized = false;
            eventBus.clearAllQueues();

            if (restartLlm) {
                processManager.stopServices();
            }

            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            tryInitEngine();

            if (asrWorker != null) asrWorker = null;
            if (ttsWorker != null) ttsWorker = null;
            asrWorker = new AsrWorker(audioBridge, this, env, config);
            ttsWorker = new TtsWorker(audioBridge, this, env, config);
            threadPool.getAsrWorker().execute(asrWorker);
            threadPool.getTtsWorker().execute(ttsWorker);
            ttsWorker.initEngine();
            if (ttsWorker.isEngineInitialized()) {
                state.setTtsReady(true);
                env.displayMessageToPlayer("\u00a7b[\u5929\u6781] \u00a7f\u7075\u97f3\u5171\u9e23\u5df2\u5c31\u7eea");
            }
            initialized = true;

            if (restartLlm) {
                new Thread(() -> processManager.startLlmServer(), "LLM-Process-Starter").start();
            }

            if (onDone != null) onDone.run();
        }, "Tianshu-Restarter").start();
    }

    public void downloadTtsModel(TtsModelInfo info, DownloadProgressCallback callback) {
        new Thread(() -> {
            try {
                Path ttsBasePath = config.getTtsBasePath();
                Path modelDir = ttsBasePath.resolve(info.name);
                HuggingFaceDownloader downloader = new HuggingFaceDownloader(env);

                callback.onProgress("TTS:探测网络", 0);
                downloader.downloadModelFiles(info.id, modelDir, "main", true, 3);

                if (info.needVocoder) {
                    callback.onProgress("TTS:下载声码器", 50);
                    Path vocoderDir = modelDir.resolve("vocoders");
                    downloader.downloadVocoder(vocoderDir, 3);
                }

                callback.onProgress("TTS:完成", 100);
                callback.onComplete();
            } catch (Exception e) {
                env.error("TTS 模型下载失败: " + info.name, e);
                callback.onError("下载失败: " + e.getMessage());
            }
        }, "TTS-Downloader-" + info.name).start();
    }

    public String resolveVocoderPath(Path modelDir) {
        Path vocoderDir = modelDir.resolve("vocoders");
        File dir = vocoderDir.toFile();
        if (!dir.exists() || !dir.isDirectory()) return null;

        File[] files = dir.listFiles();
        if (files == null) return null;

        for (File f : files) {
            if (f.isFile() && f.getName().toLowerCase().endsWith(".onnx")) {
                return f.getAbsolutePath();
            }
        }

        for (File subDir : files) {
            if (subDir.isDirectory()) {
                File[] subFiles = subDir.listFiles();
                if (subFiles != null) {
                    for (File f : subFiles) {
                        if (f.isFile() && f.getName().toLowerCase().endsWith(".onnx")) {
                            return f.getAbsolutePath();
                        }
                    }
                }
            }
        }
        return null;
    }

    public TtsModelInfo resolveCurrentTtsModelInfo() {
        Path modelPath = config.getTtsModelPath();
        if (modelPath == null) return null;
        String dirName = modelPath.getFileName() != null ? modelPath.getFileName().toString() : null;
        if (dirName == null) return null;

        List<TtsModelInfo> catalog = ModelManager.loadTtsModelCatalog();
        for (TtsModelInfo info : catalog) {
            if (dirName.equals(info.name)) return info;
        }
        return null;
    }
}
