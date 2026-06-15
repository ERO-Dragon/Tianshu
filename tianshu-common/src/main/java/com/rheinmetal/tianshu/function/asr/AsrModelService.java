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
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public class AsrModelService {

    public interface DownloadProgressCallback {
        void onProgress(String label, int percent);
        void onComplete();
        void onError(String message);

        default void onCancelled() {
        }
    }

    public record DownloadStatus(boolean downloading, boolean paused, boolean cancelling, String activeModelKey, String label, int progress) {
        public static DownloadStatus idle() {
            return new DownloadStatus(false, false, false, "", "", 0);
        }
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
    private final AtomicLong downloadSessionSequence = new AtomicLong(0L);
    private final AtomicReference<DownloadTask> activeDownload = new AtomicReference<>();

    public AsrModelService(IGameEnvironment env, ITianshuConfig config, IAudioBridge audioBridge, ProtocolExecutorManager executorManager, Supplier<AsrEngine> engineSupplier, BooleanSupplier readySupplier) {
        this.env = env;
        this.config = config;
        this.audioBridge = audioBridge;
        this.executorManager = executorManager;
        this.engineSupplier = engineSupplier;
        this.readySupplier = readySupplier;
        this.downloadCoordinator = new AsrModelDownloadCoordinator(env);
    }

    public AsrModelInfo resolveCurrentModelInfo() {
        String configured = config.getCustomAsrName();
        if (configured == null || configured.isBlank()) {
            return null;
        }
        return AsrModelManager.getModelByLocalKey(configured.trim());
    }

    public Path resolveModelDir(AsrModelInfo info) {
        if (info == null || info.localKey().isBlank()) return null;
        return config.getAsrBasePath().resolve("model").resolve(info.localKey());
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
            env.error("Failed to delete ASR model", e);
        }
    }

    public void downloadModel(String modelKey, String githubProxyUrl, DownloadProgressCallback callback) {
        AsrModelInfo info = AsrModelManager.getModelByLocalKey(modelKey);
        if (info == null) {
            notifyDownloadError(callback, "ASR model info is empty");
            return;
        }
        downloadModel(info, githubProxyUrl, callback);
    }

    public synchronized void downloadModel(AsrModelInfo info, String githubProxyUrl, DownloadProgressCallback callback) {
        if (info == null) {
            notifyDownloadError(callback, "ASR model info is empty");
            return;
        }
        DownloadTask task = new DownloadTask(
                downloadSessionSequence.incrementAndGet(),
                info.localKey(),
                downloadCoordinator.newSession()
        );
        if (!activeDownload.compareAndSet(null, task)) {
            notifyDownloadError(callback, "ASR model download is already running");
            return;
        }
        task.updateStatus(new DownloadStatus(true, false, false, task.modelKey(), "Preparing", 0));
        ProtocolTaskHandle handle = executorManager.submit(
                ProtocolTaskSpec.builder()
                        .moduleId("module.asr")
                        .lane(ExecutionLane.IO)
                        .concurrencyKey("module.asr:model.download")
                        .maxConcurrency(1)
                        .queueCapacity(1)
                        .build(),
                () -> {
                    try {
                        task.session().download(info, resolveModelDir(info), githubProxyUrl, new AsrModelDownloader.DownloadProgressCallback() {
                            @Override
                            public void onProgress(String label, int percent) {
                                if (!isCurrentTask(task)) {
                                    return;
                                }
                                task.updateStatus(new DownloadStatus(true, task.session().isPaused(), task.session().isCancelled(), task.modelKey(), label == null ? "" : label, Math.max(0, Math.min(100, percent))));
                                if (callback != null) {
                                    callback.onProgress(label, percent);
                                }
                            }

                            @Override
                            public void onComplete() {
                                if (!finishTask(task)) {
                                    return;
                                }
                                if (callback != null) {
                                    callback.onComplete();
                                }
                            }

                            @Override
                            public void onError(String message) {
                                if (!finishTask(task)) {
                                    return;
                                }
                                if (callback == null) {
                                    return;
                                }
                                if (task.session().isCancelled()) {
                                    callback.onCancelled();
                                } else {
                                    callback.onError(message);
                                }
                            }
                        });
                    } catch (Exception e) {
                        if (!finishTask(task)) {
                            return;
                        }
                        if (callback == null) {
                            return;
                        }
                        if (task.session().isCancelled()) {
                            callback.onCancelled();
                        } else {
                            callback.onError(e.getMessage() == null ? "ASR model download failed" : e.getMessage());
                        }
                    }
                }
        );
        if (handle.state() == ProtocolTaskState.REJECTED) {
            activeDownload.compareAndSet(task, null);
            notifyDownloadError(callback, "ASR model download queue is full");
        }
    }

    public DownloadStatus downloadStatus() {
        DownloadTask task = activeDownload.get();
        return task == null ? DownloadStatus.idle() : task.status();
    }

    public void downloadModelSync(AsrModelInfo info, Path targetDir, String githubProxyUrl, DownloadProgressCallback callback) throws Exception {
        downloadCoordinator.newSession().download(info, targetDir, githubProxyUrl, new AsrModelDownloader.DownloadProgressCallback() {
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
        DownloadTask task = activeDownload.get();
        return task != null && task.session().isPaused();
    }

    public void pauseDownload() {
        DownloadTask task = activeDownload.get();
        if (task == null) {
            return;
        }
        task.session().pause();
        DownloadStatus current = task.status();
        task.updateStatus(new DownloadStatus(true, true, false, task.modelKey(), current.label(), current.progress()));
    }

    public boolean pauseDownload(String modelKey) {
        if (!isActiveModel(modelKey)) {
            return false;
        }
        pauseDownload();
        return true;
    }

    public void resumeDownload() {
        DownloadTask task = activeDownload.get();
        if (task == null) {
            return;
        }
        task.session().resume();
        DownloadStatus current = task.status();
        task.updateStatus(new DownloadStatus(true, false, false, task.modelKey(), current.label(), current.progress()));
    }

    public boolean resumeDownload(String modelKey) {
        if (!isActiveModel(modelKey)) {
            return false;
        }
        resumeDownload();
        return true;
    }

    public void cancelDownload() {
        DownloadTask task = activeDownload.get();
        if (task == null) {
            return;
        }
        task.session().cancel();
        DownloadStatus current = task.status();
        task.updateStatus(new DownloadStatus(true, false, true, task.modelKey(), "Cancelling", current.progress()));
    }

    public boolean cancelDownload(String modelKey) {
        if (!isActiveModel(modelKey)) {
            return false;
        }
        cancelDownload();
        return true;
    }

    public void preview(PreviewCallback callback) {
        AsrEngine engine = engineSupplier.get();
        if (!readySupplier.getAsBoolean() || engine == null) {
            callback.onError("ASR engine is not ready. Download and load a model first.");
            callback.onFinish();
            return;
        }
        submitPreview(engine, callback, false);
    }

    public void preview(AsrModelInfo info, PreviewCallback callback) {
        if (info == null) {
            callback.onError("ASR preview model is empty.");
            callback.onFinish();
            return;
        }
        Path modelDir = resolveModelDir(info);
        if (modelDir == null || !AsrModelManager.isModelDownloaded(info, config.getAsrBasePath().resolve("model"))) {
            callback.onError("ASR preview model is not fully downloaded.");
            callback.onFinish();
            return;
        }
        if (!previewRunning.compareAndSet(false, true)) {
            callback.onError("ASR preview is already running.");
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
                () -> runModelPreview(info, modelDir, callback)
        );
    }

    private void submitPreview(AsrEngine engine, PreviewCallback callback, boolean closeEngineAfterPreview) {
        if (!previewRunning.compareAndSet(false, true)) {
            callback.onError("ASR preview is already running.");
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

    private void runModelPreview(AsrModelInfo info, Path modelDir, PreviewCallback callback) {
        AsrEngine previewEngine = new AsrEngine(env);
        try {
            if (!previewEngine.initialize(info, modelDir, null)) {
                callback.onError("ASR preview model initialization failed.");
                previewRunning.set(false);
                callback.onFinish();
                return;
            }
            runPreview(previewEngine, callback, false);
        } catch (Exception e) {
            env.error("ASR preview model failed", e);
            callback.onError("ASR preview model failed: " + e.getMessage());
            previewRunning.set(false);
            callback.onFinish();
        } finally {
            previewEngine.shutdown();
        }
    }

    private void runPreview(AsrEngine engine, PreviewCallback callback, boolean closeEngineAfterPreview) {
        try {
            env.info("ASR preview: start recording");
            audioBridge.startRecording();
            callback.onReady();

            Thread.sleep(5000);
            if (!previewRunning.get()) return;

            byte[] audioData = audioBridge.stopRecording();
            if (audioData == null || audioData.length == 0) {
                callback.onError("No audio data was captured. Please check the microphone.");
                return;
            }

            env.info("ASR preview: recording complete, audio length=" + audioData.length + " bytes");
            String result = engine.recognizeComplete(audioData);

            if (result != null && !result.isEmpty()) {
                env.info("ASR preview: recognition succeeded, text=" + result);
                callback.onResult(result);
            } else {
                callback.onError("No speech content was recognized. Please try speaking more clearly.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            callback.onError("ASR preview was interrupted.");
        } catch (Exception e) {
            env.error("ASR preview failed", e);
            callback.onError("ASR preview failed: " + e.getMessage());
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

    private boolean isCurrentTask(DownloadTask task) {
        DownloadTask current = activeDownload.get();
        return current != null && current.sessionId() == task.sessionId();
    }

    private boolean finishTask(DownloadTask task) {
        return activeDownload.compareAndSet(task, null);
    }

    private boolean isActiveModel(String modelKey) {
        DownloadTask task = activeDownload.get();
        return task != null && AsrModelManager.localKeysEqual(modelKey, task.modelKey());
    }

    private void notifyDownloadError(DownloadProgressCallback callback, String message) {
        if (callback != null) {
            callback.onError(message);
        }
    }

    private static final class DownloadTask {
        private final long sessionId;
        private final String modelKey;
        private final AsrModelDownloadCoordinator.DownloadSession session;
        private final AtomicReference<DownloadStatus> status = new AtomicReference<>();

        private DownloadTask(long sessionId, String modelKey, AsrModelDownloadCoordinator.DownloadSession session) {
            this.sessionId = sessionId;
            this.modelKey = modelKey == null ? "" : modelKey.trim();
            this.session = session;
            this.status.set(new DownloadStatus(true, false, false, this.modelKey, "", 0));
        }

        private long sessionId() {
            return sessionId;
        }

        private String modelKey() {
            return modelKey;
        }

        private AsrModelDownloadCoordinator.DownloadSession session() {
            return session;
        }

        private DownloadStatus status() {
            return status.get();
        }

        private void updateStatus(DownloadStatus next) {
            status.set(next == null ? DownloadStatus.idle() : next);
        }
    }
}
