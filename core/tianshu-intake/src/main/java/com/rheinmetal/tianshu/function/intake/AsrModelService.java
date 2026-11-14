package com.rheinmetal.tianshu.function.intake;

import com.rheinmetal.tianshu.function.intake.AsrProtocolAdapter;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.intake.settings.IntakeConfiguration;
import com.rheinmetal.tianshu.function.intake.download.AsrModelDownloadCoordinator;
import com.rheinmetal.tianshu.function.intake.engine.AsrEngine;
import com.rheinmetal.tianshu.function.intake.model.AsrModelInfo;
import com.rheinmetal.tianshu.function.intake.model.AsrModelManager;
import com.rheinmetal.tianshu.model.ModelAvailabilitySnapshot;
import com.rheinmetal.tianshu.model.lifecycle.ModelAvailabilityRefresher;
import com.rheinmetal.tianshu.model.lifecycle.ModelDeleteCallback;
import com.rheinmetal.tianshu.model.lifecycle.ModelDeletionRunner;
import com.rheinmetal.tianshu.model.lifecycle.ModelDownloadCallback;
import com.rheinmetal.tianshu.model.ModelDownloadProgress;
import com.rheinmetal.tianshu.model.ModelDownloadStage;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The speech model's public service face: catalog availability, download, delete and preview.
 *
 * <p><b>Download lifetime.</b> A download belongs to the IO lane, not to the module instance that started
 * it: leaving the world must not throw away a 1.3 GB model transfer. The task therefore keeps running
 * after {@link #close()}, and the downloader cleans the artifacts of a model before it starts a new
 * transfer of that same model — which is what makes a crash leftover self-healing without a startup sweep
 * deleting a staging directory another task is still writing.
 */
public class AsrModelService {

    public record DownloadStatus(boolean downloading, boolean paused, boolean cancelling, String activeModelKey, ModelDownloadProgress progress) {
        public static DownloadStatus idle() {
            return new DownloadStatus(false, false, false, "", ModelDownloadProgress.stage(ModelDownloadStage.CHECKING_NETWORK, 0, ""));
        }
    }

    public interface PreviewCallback {
        void onReady();
        void onResult(String text);
        void onError(String message);
        void onFinish();
    }

    private final IGameEnvironment env;
    private final IntakeConfiguration config;
    private final ModuleExecutionAccess executorManager;
    private final AsrModelDownloadCoordinator downloadCoordinator;
    private final AsrPreviewCoordinator previewCoordinator;
    private final Consumer<ModuleStatus> moduleStatusSink;
    private final ModelDeletionRunner deletionRunner;
    private final ModelAvailabilityRefresher availabilityRefresher;
    private final AtomicLong downloadSessionSequence = new AtomicLong(0L);
    private final AtomicReference<DownloadTask> activeDownload = new AtomicReference<>();
    private final AtomicReference<ProtocolTaskHandle> activeDownloadHandle = new AtomicReference<>();

    public AsrModelService(
            IGameEnvironment env,
            IntakeConfiguration config,
            IAudioBridge audioBridge,
            ModuleExecutionAccess executorManager,
            Consumer<ModuleStatus> moduleStatusSink
    ) {
        this.env = env;
        this.config = config;
        this.executorManager = executorManager;
        this.moduleStatusSink = moduleStatusSink == null ? ignored -> {} : moduleStatusSink;
        this.downloadCoordinator = new AsrModelDownloadCoordinator(env);
        this.availabilityRefresher = new ModelAvailabilityRefresher(
                AsrProtocolAdapter.MODULE_ID, executorManager, env, this::buildAvailabilitySnapshot);
        this.deletionRunner = new ModelDeletionRunner(AsrProtocolAdapter.MODULE_ID, executorManager, availabilityRefresher);
        this.previewCoordinator = new AsrPreviewCoordinator(
                env,
                audioBridge,
                executorManager,
                AsrPreviewCoordinator.DEFAULT_RECORDING_WINDOW
        );
        refreshAvailabilityAsync();
    }

    public Path resolveModelDir(AsrModelInfo info) {
        if (info == null || info.localKey().isBlank()) return null;
        return config.getAsrBasePath().resolve("model").resolve(info.localKey());
    }

    private Path modelBasePath() {
        return config.getAsrBasePath().resolve("model");
    }

    /**
     * Publishes one availability snapshot without blocking the caller that constructed the service.
     *
     * <p>This is a read of the model directory, so it belongs to the IO lane; the settings page reads the
     * last snapshot and refreshes it again when it opens.
     */
    private void refreshAvailabilityAsync() {
        executorManager.submit(
                ProtocolTaskSpec.builder()
                        .moduleId(AsrProtocolAdapter.MODULE_ID)
                        .lane(ExecutionLane.IO)
                        .concurrencyKey("module.asr:model.availability")
                        .maxConcurrency(1)
                        .queueCapacity(1)
                        .build(),
                availabilityRefresher::refreshNow
        );
    }

    public ModelAvailabilitySnapshot modelAvailability() {
        return availabilityRefresher.snapshot();
    }

    public void refreshModelAvailabilityAsync(Runnable completion) {
        availabilityRefresher.refreshAsync(completion);
    }

    private ModelAvailabilitySnapshot buildAvailabilitySnapshot() {
        Map<String, ModelAvailabilitySnapshot.Entry> entries = new LinkedHashMap<>();
        Path baseDir = modelBasePath();
        for (AsrModelInfo info : AsrModelManager.getAllModels()) {
            if (info == null || info.localKey() == null || info.localKey().isBlank()) {
                continue;
            }
            entries.put(info.localKey(), new ModelAvailabilitySnapshot.Entry(
                    AsrModelManager.isModelDownloaded(info, baseDir), 0L
            ));
        }
        return new ModelAvailabilitySnapshot(entries, true, System.currentTimeMillis());
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
        deletionRunner.deleteAsync(() -> deleteModel(info), callback);
    }

    public boolean isDeleting() {
        return deletionRunner.isDeleting();
    }

    public void downloadModel(String modelKey, String githubProxyUrl, ModelDownloadCallback callback) {
        AsrModelInfo info = AsrModelManager.getModelByLocalKey(modelKey);
        if (info == null) {
            notifyDownloadError(callback, "ASR model info is empty");
            return;
        }
        downloadModel(info, githubProxyUrl, callback);
    }

    public synchronized void downloadModel(AsrModelInfo info, String githubProxyUrl, ModelDownloadCallback callback) {
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
            publishFailed("tianshu.runtime.module.asr.download_busy");
            notifyDownloadError(callback, "ASR model download is already running");
            return;
        }
        task.updateStatus(new DownloadStatus(true, false, false, task.modelKey(), ModelDownloadProgress.stage(ModelDownloadStage.CHECKING_NETWORK, 0, "download.prepare")));
        publishWaiting("tianshu.runtime.module.asr.download_started");
        ProtocolTaskHandle handle = executorManager.submit(downloadTaskSpec(), () -> runDownload(task, info, githubProxyUrl, callback));
        activeDownloadHandle.set(handle);
        if (handle.state() == ProtocolTaskState.REJECTED) {
            finishTask(task);
            publishFailed("tianshu.runtime.module.asr.download_queue_full");
            notifyDownloadError(callback, "ASR model download queue is full");
            return;
        }
        if (task.session().isCancelled()) {
            // The GUI cancelled between the permit being requested and the task being queued.
            handle.cancel("model_download_cancelled");
        }
    }

    private static ProtocolTaskSpec downloadTaskSpec() {
        return ProtocolTaskSpec.builder()
                .moduleId(AsrProtocolAdapter.MODULE_ID)
                .lane(ExecutionLane.IO)
                .concurrencyKey("module.asr:model.download")
                .maxConcurrency(1)
                .queueCapacity(1)
                .build();
    }

    /**
     * Runs one transfer and reports its outcome exactly once.
     *
     * <p>The {@code finally} is the single cleanup point, so an exit the callback path cannot represent —
     * a fatal JVM {@code Error} — still clears {@link #activeDownload} and leaves the next download usable.
     * A cancelled transfer and a failed one are told apart here rather than by asking the session after the
     * fact.
     */
    private void runDownload(DownloadTask task, AsrModelInfo info, String githubProxyUrl, ModelDownloadCallback callback) {
        boolean succeeded = false;
        boolean cancelled = false;
        Exception failure = null;
        try {
            task.session().download(info, resolveModelDir(info), githubProxyUrl, progressForwarder(task, callback));
            succeeded = true;
        } catch (Exception downloadFailure) {
            // Cancellation reaches this point as an ordinary IOException from whichever layer noticed it
            // (gate, HTTP transport or archive), so the session flag — not the exception type — is the
            // thing that tells "the player cancelled" apart from "the transfer failed".
            if (task.session().isCancelled()) {
                cancelled = true;
            } else {
                failure = downloadFailure;
            }
        } finally {
            completeDownload(task, succeeded, cancelled, failure, callback);
        }
    }

    private ModelDownloadCallback progressForwarder(DownloadTask task, ModelDownloadCallback callback) {
        return new ModelDownloadCallback() {
            @Override
            public void onProgress(ModelDownloadProgress progress) {
                if (!isCurrentTask(task)) {
                    return;
                }
                task.updateStatus(new DownloadStatus(true, task.session().isPaused(), task.session().isCancelled(), task.modelKey(), progress));
                if (callback != null) {
                    callback.onProgress(progress);
                }
            }

            @Override
            public void onComplete() {
                // Completion is decided by the transfer returning, not by the last callback it fires.
            }

            @Override
            public void onError(String message) {
                // Failures arrive as an exception from the transfer; see runDownload.
            }
        };
    }

    private void completeDownload(DownloadTask task, boolean succeeded, boolean cancelled, Exception failure, ModelDownloadCallback callback) {
        if (!finishTask(task)) {
            return;
        }
        availabilityRefresher.refreshNow();
        if (cancelled) {
            publishWaiting("tianshu.runtime.module.asr.download_cancelled");
            if (callback != null) {
                callback.onCancelled();
            }
            return;
        }
        if (failure != null) {
            env.error("asr.model.download.failed", failure);
        }
        if (!succeeded) {
            publishFailed("tianshu.runtime.module.asr.download_failed");
            if (callback != null) {
                callback.onError("tianshu.gui.asr.error.download_failed");
            }
            return;
        }
        publishWaiting("tianshu.runtime.module.asr.download_complete");
        if (callback != null) {
            callback.onComplete();
        }
    }

    public DownloadStatus downloadStatus() {
        DownloadTask task = activeDownload.get();
        return task == null ? DownloadStatus.idle() : task.status();
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
        task.updateStatus(new DownloadStatus(true, true, false, task.modelKey(), withStage(current.progress(), ModelDownloadStage.PAUSED)));
        publishWaiting("tianshu.runtime.module.asr.download_paused");
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
        task.updateStatus(new DownloadStatus(true, false, false, task.modelKey(), withStage(current.progress(), ModelDownloadStage.DOWNLOADING)));
        publishWaiting("tianshu.runtime.module.asr.download_resumed");
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
        ProtocolTaskHandle handle = activeDownloadHandle.get();
        if (handle != null) {
            handle.cancel("model_download_cancelled");
        }
        DownloadStatus current = task.status();
        task.updateStatus(new DownloadStatus(true, false, true, task.modelKey(), withStage(current.progress(), ModelDownloadStage.CANCELLING)));
        publishWaiting("tianshu.runtime.module.asr.download_cancelling");
    }

    public boolean cancelDownload(String modelKey) {
        if (!isActiveModel(modelKey)) {
            return false;
        }
        cancelDownload();
        return true;
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

    /** Releases the preview engine; an active download deliberately keeps running, see the class doc. */
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
            if (!engine.initialize(info, modelDir)) {
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
        boolean finished = task != null && activeDownload.compareAndSet(task, null);
        if (finished) {
            activeDownloadHandle.set(null);
        }
        return finished;
    }

    private static ModelDownloadProgress withStage(ModelDownloadProgress progress, ModelDownloadStage stage) {
        ModelDownloadProgress current = progress == null
                ? ModelDownloadProgress.stage(stage, 0, "")
                : progress;
        return new ModelDownloadProgress(stage, current.percent(), current.downloadedBytes(), current.totalBytes(), current.detailCode());
    }

    private void publishWaiting(String messageKey) {
        moduleStatusSink.accept(ModuleStatuses.waitingKeyed(AsrProtocolAdapter.MODULE_ID, messageKey));
    }

    private void publishFailed(String messageKey) {
        moduleStatusSink.accept(ModuleStatuses.failedKeyed(AsrProtocolAdapter.MODULE_ID, messageKey));
    }

    private boolean isActiveModel(String modelKey) {
        DownloadTask task = activeDownload.get();
        return task != null && AsrModelManager.localKeysEqual(modelKey, task.modelKey());
    }

    private void notifyDownloadError(ModelDownloadCallback callback, String message) {
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
            this.status.set(new DownloadStatus(true, false, false, this.modelKey, ModelDownloadProgress.stage(ModelDownloadStage.CHECKING_NETWORK, 0, "download.prepare")));
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