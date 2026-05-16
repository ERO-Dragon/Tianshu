package com.rheinmetal.tianshu.function.asr;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.function.asr.download.AsrModelDownloadCoordinator;
import com.rheinmetal.tianshu.function.asr.engine.AsrEngine;
import com.rheinmetal.tianshu.model.AsrModelDownloader;
import com.rheinmetal.tianshu.model.AsrModelInfo;
import com.rheinmetal.tianshu.model.AsrModelManager;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutorManager;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public class AsrModelService {

    public interface DownloadProgressCallback {
        void onProgress(String label, int percent);
        void onComplete();
        void onError(String message);
    }

    public interface PreviewCallback {
        void onReady();
        void onResult(String text);
        void onError(String message);
        void onFinish();
    }

    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final IAudioBridge audioBridge;
    private final ProtocolExecutorManager executorManager;
    private final AsrModelDownloadCoordinator downloadCoordinator;
    private final Supplier<AsrEngine> engineSupplier;
    private final BooleanSupplier readySupplier;
    private final AtomicBoolean previewRunning = new AtomicBoolean(false);

    public AsrModelService(IGameEnvironment env, ITianshuConfig config, IAudioBridge audioBridge, ProtocolExecutorManager executorManager, Supplier<AsrEngine> engineSupplier, BooleanSupplier readySupplier) {
        this.env = env;
        this.config = config;
        this.audioBridge = audioBridge;
        this.executorManager = executorManager;
        this.engineSupplier = engineSupplier;
        this.readySupplier = readySupplier;
        this.downloadCoordinator = new AsrModelDownloadCoordinator(env, config);
    }

    public AsrModelInfo resolveCurrentModelInfo() {
        Path modelPath = config.getAsrModelPath();
        if (modelPath == null || modelPath.getFileName() == null) {
            return null;
        }
        String dirName = modelPath.getFileName().toString();
        AsrModelInfo info = AsrModelManager.getModelByName(dirName);
        if (info != null) return info;
        return AsrModelManager.getModelById(dirName);
    }

    public Path resolveModelDir(AsrModelInfo info) {
        if (info == null || info.name == null) return null;
        return config.getAsrBasePath().resolve("model").resolve(info.name);
    }

    public boolean hasModelContent(AsrModelInfo info) {
        Path modelDir = resolveModelDir(info);
        if (modelDir == null || !Files.exists(modelDir)) return false;
        return AsrModelManager.isModelDownloaded(info, config.getAsrBasePath().resolve("model"));
    }

    public void deleteModel(AsrModelInfo info) {
        Path modelDir = resolveModelDir(info);
        if (modelDir == null || !Files.exists(modelDir)) return;
        try {
            deleteRecursively(modelDir);
        } catch (IOException e) {
            env.error("删除 ASR 模型失败", e);
        }
    }

    public void downloadModel(AsrModelInfo info, String githubProxyUrl, DownloadProgressCallback callback) {
        if (info == null) {
            callback.onError("ASR 模型信息为空");
            return;
        }
        executorManager.submit(
                ProtocolTaskSpec.builder()
                        .moduleId("module.asr")
                        .lane(ExecutionLane.IO)
                        .concurrencyKey("module.asr:model.download")
                        .maxConcurrency(1)
                        .queueCapacity(1)
                        .build(),
                () -> {
                    try {
                        downloadCoordinator.download(info, resolveModelDir(info), githubProxyUrl, new AsrModelDownloader.DownloadProgressCallback() {
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
                    } catch (Exception e) {
                        callback.onError(e.getMessage() == null ? "ASR 模型下载失败" : e.getMessage());
                    }
                }
        );
    }

    public void downloadModelSync(AsrModelInfo info, Path targetDir, String githubProxyUrl, DownloadProgressCallback callback) throws Exception {
        downloadCoordinator.download(info, targetDir, githubProxyUrl, new AsrModelDownloader.DownloadProgressCallback() {
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

    public boolean isDownloadPaused() {
        return downloadCoordinator.isPaused();
    }

    public void pauseDownload() {
        downloadCoordinator.pause();
    }

    public void resumeDownload() {
        downloadCoordinator.resume();
    }

    public void cancelDownload() {
        downloadCoordinator.cancel();
    }

    public void preview(PreviewCallback callback) {
        AsrEngine engine = engineSupplier.get();
        if (!readySupplier.getAsBoolean() || engine == null) {
            callback.onError("ASR 引擎未就绪，请先下载并加载模型");
            callback.onFinish();
            return;
        }
        submitPreview(engine, callback, false);
    }

    public void preview(AsrModelInfo info, PreviewCallback callback) {
        if (info == null) {
            callback.onError("ASR 试听模型为空");
            callback.onFinish();
            return;
        }
        Path modelDir = resolveModelDir(info);
        if (modelDir == null || !AsrModelManager.isModelDownloaded(info, config.getAsrBasePath().resolve("model"))) {
            callback.onError("ASR 试听模型未下载完整");
            callback.onFinish();
            return;
        }
        if (!previewRunning.compareAndSet(false, true)) {
            callback.onError("ASR 试听正在播放中，请等待完成");
            callback.onFinish();
            return;
        }
        executorManager.submit(
                ProtocolTaskSpec.builder()
                        .moduleId("module.asr")
                        .lane(ExecutionLane.ASR_STREAM)
                        .concurrencyKey("module.asr:preview")
                        .maxConcurrency(1)
                        .queueCapacity(1)
                        .build(),
                () -> runDraftPreview(info, modelDir, callback)
        );
    }

    private void submitPreview(AsrEngine engine, PreviewCallback callback, boolean closeEngineAfterPreview) {
        if (!previewRunning.compareAndSet(false, true)) {
            callback.onError("ASR 试听正在播放中，请等待完成");
            callback.onFinish();
            return;
        }
        executorManager.submit(
                ProtocolTaskSpec.builder()
                        .moduleId("module.asr")
                        .lane(ExecutionLane.ASR_STREAM)
                        .concurrencyKey("module.asr:preview")
                        .maxConcurrency(1)
                        .queueCapacity(1)
                        .build(),
                () -> runPreview(engine, callback, closeEngineAfterPreview)
        );
    }

    public boolean isPreviewRunning() {
        return previewRunning.get();
    }

    public void stopPreview() {
        if (!previewRunning.getAndSet(false)) {
            return;
        }
        try {
            audioBridge.stopRecording();
        } catch (Throwable ignored) {}
    }

    private void runDraftPreview(AsrModelInfo info, Path modelDir, PreviewCallback callback) {
        AsrEngine previewEngine = new AsrEngine(env);
        try {
            if (!previewEngine.initialize(info, modelDir, null)) {
                callback.onError("ASR 试听模型初始化失败");
                previewRunning.set(false);
                callback.onFinish();
                return;
            }
            runPreview(previewEngine, callback, false);
        } catch (Exception e) {
            env.error("ASR 草稿模型试听失败", e);
            callback.onError("ASR 草稿模型试听失败: " + e.getMessage());
            previewRunning.set(false);
            callback.onFinish();
        } finally {
            previewEngine.shutdown();
        }
    }

    private void runPreview(AsrEngine engine, PreviewCallback callback, boolean closeEngineAfterPreview) {
        try {
            env.info("ASR 试听: 开始录音");
            audioBridge.startRecording();
            callback.onReady();

            Thread.sleep(5000);
            if (!previewRunning.get()) return;

            byte[] audioData = audioBridge.stopRecording();
            if (audioData == null || audioData.length == 0) {
                callback.onError("未采集到音频数据，请检查麦克风");
                return;
            }

            env.info("ASR 试听: 录音完成，音频长度=" + audioData.length + " bytes");
            String result = engine.recognizeComplete(audioData);

            if (result != null && !result.isEmpty()) {
                env.info("ASR 试听: 识别成功，文本=" + result);
                callback.onResult(result);
            } else {
                callback.onError("未识别到语音内容，请尝试说话更清晰");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            callback.onError("ASR 试听被中断");
        } catch (Exception e) {
            env.error("ASR 试听失败", e);
            callback.onError("ASR 试听失败: " + e.getMessage());
        } finally {
            audioBridge.stopRecording();
            if (closeEngineAfterPreview && engine != null) {
                engine.shutdown();
            }
            previewRunning.set(false);
            callback.onFinish();
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
