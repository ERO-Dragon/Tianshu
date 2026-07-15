package com.rheinmetal.tianshu.function.asr;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.asr.settings.AsrConfiguration;
import com.rheinmetal.tianshu.function.asr.download.AsrModelDownloadCoordinator;
import com.rheinmetal.tianshu.function.asr.engine.AsrEngine;
import com.rheinmetal.tianshu.model.AsrModelDownloader;
import com.rheinmetal.tianshu.model.AsrModelInfo;
import com.rheinmetal.tianshu.model.AsrModelManager;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ModuleExecutionAccess;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskState;
import com.rheinmetal.tianshu.protocol.status.ModuleStatuses;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class AsrModelService {

    public interface DownloadProgressCallback {
        void onProgress(String label, int percent);
        void onComplete();
        void onError(String message);

        default void onCancelled() {
        }
    }

    public interface ModelDeleteCallback {
        void onComplete(boolean deleted);
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
    private final AsrConfiguration config;
    private final ModuleExecutionAccess executorManager;
    private final AsrModelDownloadCoordinator downloadCoordinator;
    private final AsrPreviewCoordinator previewCoordinator;
    private final Supplier<AsrEngine> engineSupplier;
    private final BooleanSupplier readySupplier;
    private final Consumer<ModuleStatus> moduleStatusSink;
    private final AtomicBoolean deletingModel = new AtomicBoolean(false);
    private final AtomicLong downloadSessionSequence = new AtomicLong(0L);
    private final AtomicReference<DownloadTask> activeDownload = new AtomicReference<>();

    public AsrModelService(IGameEnvironment env, AsrConfiguration config, IAudioBridge audioBridge, ModuleExecutionAccess executorManager, Supplier<AsrEngine> engineSupplier, BooleanSupplier readySupplier) {
        this(env, config, audioBridge, executorManager, engineSupplier, readySupplier, null);
    }

    public AsrModelService(
            IGameEnvironment env,
            AsrConfiguration config,
            IAudioBridge audioBridge,
            ModuleExecutionAccess executorManager,
            Supplier<AsrEngine> engineSupplier,
            BooleanSupplier readySupplier,
            Consumer<ModuleStatus> moduleStatusSink
    ) {
        this.env = env;
        this.config = config;
        this.executorManager = executorManager;
        this.engineSupplier = engineSupplier;
        this.readySupplier = readySupplier;
        this.moduleStatusSink = moduleStatusSink == null ? ignored -> {} : moduleStatusSink;
        this.downloadCoordinator = new AsrModelDownloadCoordinator(env);
        this.previewCoordinator = new AsrPreviewCoordinator(
                env,
                audioBridge,
                executorManager,
                AsrPreviewCoordinator.DEFAULT_RECORDING_WINDOW
        );
        scheduleStartupCleanup();
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

    private Path modelBasePath() {
        return config.getAsrBasePath().resolve("model");
    }

    private void scheduleStartupCleanup() {
        executorManager.submit(
                ProtocolTaskSpec.builder()
                        .moduleId("module.asr")
                        .lane(ExecutionLane.IO)
                        .concurrencyKey("module.asr:model.cleanup")
                        .maxConcurrency(1)
                        .queueCapacity(1)
                        .build(),
                this::cleanupStaleDownloadArtifacts
        );
    }

    private void cleanupStaleDownloadArtifacts() {
        Path base = modelBasePath();
        if (!Files.isDirectory(base)) {
            return;
        }
        try (var stream = Files.list(base)) {
            for (Path path : stream.toList()) {
                String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase();
                if (Files.isDirectory(path) && (name.endsWith("-staging") || name.endsWith("-extract"))) {
                    deleteRecursively(path);
                } else if (Files.isRegularFile(path) && (name.endsWith(".tmp") || name.endsWith(".downloading"))) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (IOException e) {
            env.warn("Failed to clean stale ASR download artifacts: " + e.getMessage());
        }
    }

    public boolean hasModelContent(AsrModelInfo info) {
        Path modelDir = resolveModelDir(info);
        if (modelDir == null || !Files.exists(modelDir)) return false;
        return AsrModelManager.isModelDownloaded(info, config.getAsrBasePath().resolve("model"));
    }

    public boolean deleteModel(AsrModelInfo info) {
        Path modelDir = resolveModelDir(info);
        if (modelDir == null || !Files.exists(modelDir)) return false;
        try {
            deleteRecursively(modelDir);
            return true;
        } catch (IOException e) {
            env.error("Failed to delete ASR model", e);
            return false;
        }
    }

    public void deleteModelAsync(AsrModelInfo info, ModelDeleteCallback callback) {
        if (info == null) {
            if (callback != null) callback.onComplete(false);
            return;
        }
        if (!deletingModel.compareAndSet(false, true)) {
            if (callback != null) callback.onComplete(false);
            return;
        }
        ProtocolTaskHandle handle = executorManager.submit(
                ProtocolTaskSpec.builder()
                        .moduleId("module.asr")
                        .lane(ExecutionLane.IO)
                        .concurrencyKey("module.asr:model.delete")
                        .maxConcurrency(1)
                        .queueCapacity(2)
                        .build(),
                () -> {
                    boolean deleted;
                    try {
                        deleted = deleteModel(info);
                    } finally {
                        deletingModel.set(false);
                    }
                    if (callback != null) callback.onComplete(deleted);
                }
        );
        if (handle.state() == ProtocolTaskState.REJECTED) {
            deletingModel.set(false);
            if (callback != null) callback.onComplete(false);
        }
    }

    public boolean isDeleting() {
        return deletingModel.get();
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
            publishFailed("tianshu.presence.module.asr.download_busy", "ASR 模型下载已在进行");
            notifyDownloadError(callback, "ASR model download is already running");
            return;
        }
        task.updateStatus(new DownloadStatus(true, false, false, task.modelKey(), "Preparing", 0));
        publishWaiting("tianshu.presence.module.asr.download_started", "ASR 模型下载中");
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
                                publishReady("tianshu.presence.module.asr.download_complete", "ASR 模型下载完成");
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
                                    publishReady("tianshu.presence.module.asr.download_cancelled", "ASR 模型下载已取消");
                                    callback.onCancelled();
                                } else {
                                    publishFailed("tianshu.presence.module.asr.download_failed", "ASR 模型下载失败");
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
                            publishReady("tianshu.presence.module.asr.download_cancelled", "ASR 模型下载已取消");
                            callback.onCancelled();
                        } else {
                            publishFailed("tianshu.presence.module.asr.download_failed", "ASR 模型下载失败");
                            callback.onError(e.getMessage() == null ? "ASR model download failed" : e.getMessage());
                        }
                    }
                }
        );
        if (handle.state() == ProtocolTaskState.REJECTED) {
            activeDownload.compareAndSet(task, null);
            publishFailed("tianshu.presence.module.asr.download_queue_full", "ASR 模型下载队列已满");
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
        publishWaiting("tianshu.presence.module.asr.download_paused", "ASR 模型下载已暂停");
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
        publishWaiting("tianshu.presence.module.asr.download_resumed", "ASR 模型下载已恢复");
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
        publishWaiting("tianshu.presence.module.asr.download_cancelling", "正在取消 ASR 模型下载");
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
            callback.onError("tianshu.gui.asr.failure.engine_not_ready");
            callback.onFinish();
            return;
        }
        previewCoordinator.start(new SharedEnginePreviewOperation(engine), previewListener(callback));
    }

    public void preview(AsrModelInfo info, PreviewCallback callback) {
        if (info == null) {
            callback.onError("tianshu.gui.asr.failure.model_empty");
            callback.onFinish();
            return;
        }
        Path modelDir = resolveModelDir(info);
        if (modelDir == null || !AsrModelManager.isModelDownloaded(info, config.getAsrBasePath().resolve("model"))) {
            callback.onError("tianshu.gui.asr.failure.model_not_downloaded");
            callback.onFinish();
            return;
        }
        previewCoordinator.start(new SelectedModelPreviewOperation(info, modelDir), previewListener(callback));
    }

    public boolean isPreviewRunning() {
        return previewCoordinator.isRunning();
    }

    public void stopPreview() {
        previewCoordinator.stop();
    }

    void close() {
        previewCoordinator.close();
    }

    private AsrPreviewCoordinator.Listener previewListener(PreviewCallback callback) {
        return new AsrPreviewCoordinator.Listener() {
            @Override
            public void onReady() {
                callback.onReady();
            }

            @Override
            public void onResult(String text) {
                callback.onResult(text);
            }

            @Override
            public void onFailure(AsrPreviewCoordinator.Failure failure) {
                callback.onError(previewFailureMessage(failure));
            }

            @Override
            public void onFinish() {
                callback.onFinish();
            }
        };
    }

    private static String previewFailureMessage(AsrPreviewCoordinator.Failure failure) {
        return switch (failure.code()) {
            case CLOSED -> "tianshu.gui.asr.failure.unavailable";
            case ALREADY_RUNNING -> "tianshu.gui.asr.failure.already_running";
            case QUEUE_REJECTED -> "tianshu.gui.asr.failure.queue_unavailable";
            case PREPARE_FAILED -> "tianshu.gui.asr.failure.model_initialization";
            case CAPTURE_START_FAILED -> "tianshu.gui.asr.failure.capture_start";
            case CAPTURE_STOP_FAILED -> "tianshu.gui.asr.failure.capture_stop";
            case EMPTY_AUDIO -> "tianshu.gui.asr.failure.no_audio";
            case RECOGNITION_FAILED -> "tianshu.gui.asr.failure.recognition";
            case EMPTY_RESULT -> "tianshu.gui.asr.failure.no_speech";
        };
    }

    private static final class SharedEnginePreviewOperation implements AsrPreviewCoordinator.RecognitionOperation {
        private final AsrEngine engine;

        private SharedEnginePreviewOperation(AsrEngine engine) {
            this.engine = engine;
        }

        @Override
        public void prepare() {
        }

        @Override
        public String recognize(byte[] audio) {
            return engine.recognizeComplete(audio);
        }

        @Override
        public void close() {
        }
    }

    private final class SelectedModelPreviewOperation implements AsrPreviewCoordinator.RecognitionOperation {
        private final AsrModelInfo info;
        private final Path modelDir;
        private AsrEngine engine;

        private SelectedModelPreviewOperation(AsrModelInfo info, Path modelDir) {
            this.info = info;
            this.modelDir = modelDir;
        }

        @Override
        public void prepare() throws Exception {
            engine = new AsrEngine(env);
            if (!engine.initialize(info, modelDir, null)) {
                throw new IllegalStateException("ASR preview model initialization failed");
            }
        }

        @Override
        public String recognize(byte[] audio) {
            if (engine == null) {
                throw new IllegalStateException("ASR preview engine is not initialized");
            }
            return engine.recognizeComplete(audio);
        }

        @Override
        public void close() {
            AsrEngine current = engine;
            engine = null;
            if (current != null) {
                current.shutdown();
            }
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

    private void publishReady(String messageKey, String fallbackTitle) {
        moduleStatusSink.accept(ModuleStatuses.readyKeyed("module.asr", messageKey, fallbackTitle));
    }

    private void publishWaiting(String messageKey, String fallbackTitle) {
        moduleStatusSink.accept(ModuleStatuses.waitingKeyed("module.asr", messageKey, fallbackTitle));
    }

    private void publishFailed(String messageKey, String fallbackTitle) {
        moduleStatusSink.accept(ModuleStatuses.failedKeyed("module.asr", messageKey, fallbackTitle));
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


