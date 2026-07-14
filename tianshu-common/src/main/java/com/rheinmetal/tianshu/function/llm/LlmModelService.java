package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.function.llm.download.LlmModelDownloadCoordinator;
import com.rheinmetal.tianshu.model.LlmModelDownloader;
import com.rheinmetal.tianshu.model.LlmModelInfo;
import com.rheinmetal.tianshu.model.LlmModelManager;
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
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class LlmModelService {
    private static final long PROGRESS_UPDATE_INTERVAL_MILLIS = 200L;
    private static final String STATUS_IDLE_KEY = "tianshu.gui.llm.status.idle";
    private static final String STATUS_DOWNLOADING_KEY = "tianshu.gui.llm.status.downloading";
    private static final String STATUS_DOWNLOAD_COMPLETE_KEY = "tianshu.gui.llm.status.download_complete";
    private static final String STATUS_DOWNLOAD_FAILED_KEY = "tianshu.gui.llm.status.download_failed";
    private static final String STATUS_CANCELLED_KEY = "tianshu.gui.llm.status.cancelled";
    private static final String STATUS_CANCELLING_KEY = "tianshu.gui.llm.status.cancelling";
    private static final String ERROR_MODEL_INFO_MISSING_KEY = "tianshu.gui.llm.error.model_info_missing";
    private static final String ERROR_MODEL_PATH_UNRESOLVED_KEY = "tianshu.gui.llm.error.model_path_unresolved";
    private static final String ERROR_DOWNLOAD_BUSY_KEY = "tianshu.gui.llm.error.download_busy";
    private static final String ERROR_DOWNLOAD_QUEUE_FULL_KEY = "tianshu.gui.llm.error.download_queue_full";
    private static final String ERROR_DOWNLOAD_FAILED_KEY = "tianshu.gui.llm.error.download_failed";

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

    public record DownloadSnapshot(boolean running, boolean paused, boolean cancelling, String modelName, String label, int percent, String errorMessage, long updatedAtMillis) {
        public DownloadSnapshot {
            modelName = modelName == null ? "" : modelName.trim();
            label = label == null ? "" : label.trim();
            errorMessage = errorMessage == null ? "" : errorMessage.trim();
            percent = Math.max(0, Math.min(100, percent));
            updatedAtMillis = updatedAtMillis > 0L ? updatedAtMillis : System.currentTimeMillis();
        }

        public static DownloadSnapshot idle() {
            return new DownloadSnapshot(false, false, false, "", STATUS_IDLE_KEY, 0, "", System.currentTimeMillis());
        }
    }

    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final ModuleExecutionAccess executorManager;
    private final LlmModelDownloadCoordinator downloadCoordinator;
    private final Consumer<ModuleStatus> moduleStatusSink;
    private final AtomicReference<DownloadTask> activeDownload = new AtomicReference<>();
    private final AtomicReference<DownloadSnapshot> downloadSnapshot = new AtomicReference<>(DownloadSnapshot.idle());
    private final AtomicReference<String> deletingModelName = new AtomicReference<>("");

    public LlmModelService(IGameEnvironment env, ITianshuConfig config, ModuleExecutionAccess executorManager) {
        this(env, config, executorManager, null);
    }

    public LlmModelService(IGameEnvironment env, ITianshuConfig config, ModuleExecutionAccess executorManager, Consumer<ModuleStatus> moduleStatusSink) {
        this.env = Objects.requireNonNull(env, "env");
        this.config = Objects.requireNonNull(config, "config");
        this.executorManager = Objects.requireNonNull(executorManager, "executorManager");
        this.moduleStatusSink = moduleStatusSink == null ? ignored -> {} : moduleStatusSink;
        this.downloadCoordinator = new LlmModelDownloadCoordinator(env);
        scheduleStartupCleanup();
    }

    public List<LlmModelInfo> allModels() {
        return LlmModelManager.getAllModels().stream()
                .filter(Objects::nonNull)
                .filter(info -> info.name != null && !info.name.isBlank())
                .sorted(Comparator.comparing(info -> info.name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public List<LlmModelInfo> downloadedModels() {
        Path base = modelBasePath();
        return allModels().stream()
                .filter(info -> LlmModelManager.isModelDownloaded(info, base))
                .toList();
    }

    public LlmModelInfo resolveModel(String name) {
        return LlmModelManager.getModelByName(name);
    }

    public boolean hasModelContent(LlmModelInfo info) {
        return LlmModelManager.isModelDownloaded(info, modelBasePath());
    }

    public Path resolveModelDir(LlmModelInfo info) {
        if (info == null || info.name == null || info.name.isBlank()) return null;
        return modelBasePath().resolve(info.name);
    }

    public long modelSizeBytes(LlmModelInfo info) {
        Path modelDir = resolveModelDir(info);
        if (modelDir == null) return 0L;
        Path modelFile = modelDir.resolve(info.getModelFile());
        try {
            return Files.isRegularFile(modelFile) ? Files.size(modelFile) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    public void downloadModel(LlmModelInfo info, DownloadProgressCallback callback) {
        if (info == null) {
            if (callback != null) callback.onError(ERROR_MODEL_INFO_MISSING_KEY);
            return;
        }
        Path modelDir = resolveModelDir(info);
        if (modelDir == null) {
            if (callback != null) callback.onError(ERROR_MODEL_PATH_UNRESOLVED_KEY);
            return;
        }
        DownloadTask task = new DownloadTask(info.name, modelDir, hasModelContent(info), downloadCoordinator.newSession());
        if (!activeDownload.compareAndSet(null, task)) {
            publishFailed("tianshu.presence.module.llm.download_busy", "");
            if (callback != null) callback.onError(ERROR_DOWNLOAD_BUSY_KEY);
            return;
        }
        updateDownload(true, false, false, info.name, STATUS_DOWNLOADING_KEY, 0, "");
        publishWaiting("tianshu.presence.module.llm.download_started", "");
        ProtocolTaskHandle handle = executorManager.submit(
                ProtocolTaskSpec.builder()
                        .moduleId("module.llm")
                        .lane(ExecutionLane.IO)
                        .concurrencyKey("module.llm:model.download")
                        .maxConcurrency(1)
                        .queueCapacity(1)
                        .build(),
                () -> runDownload(task, info, callback)
        );
        if (handle.state() == ProtocolTaskState.REJECTED) {
            activeDownload.compareAndSet(task, null);
            updateDownload(false, false, false, info.name, STATUS_DOWNLOAD_FAILED_KEY, 0, ERROR_DOWNLOAD_QUEUE_FULL_KEY);
            publishFailed("tianshu.presence.module.llm.download_queue_full", "");
            if (callback != null) callback.onError(ERROR_DOWNLOAD_QUEUE_FULL_KEY);
        }
    }

    public void pauseDownload() {
        DownloadTask task = activeDownload.get();
        DownloadSnapshot current = downloadSnapshot.get();
        if (task == null || current.cancelling()) {
            return;
        }
        task.session().pause();
        updateDownload(true, true, false, task.modelName(), current.label(), current.percent(), current.errorMessage());
        publishWaiting("tianshu.presence.module.llm.download_paused", "");
    }

    public void resumeDownload() {
        DownloadTask task = activeDownload.get();
        DownloadSnapshot current = downloadSnapshot.get();
        if (task == null || current.cancelling()) {
            return;
        }
        task.session().resume();
        updateDownload(true, false, false, task.modelName(), current.label(), current.percent(), current.errorMessage());
        publishWaiting("tianshu.presence.module.llm.download_resumed", "");
    }

    public void cancelDownload() {
        DownloadTask task = activeDownload.get();
        DownloadSnapshot current = downloadSnapshot.get();
        if (task == null) {
            return;
        }
        task.session().cancel();
        executorManager.submit(
                ProtocolTaskSpec.builder()
                        .moduleId("module.llm")
                        .lane(ExecutionLane.IO)
                        .concurrencyKey("module.llm:model.download.cancel")
                        .maxConcurrency(1)
                        .queueCapacity(4)
                        .build(),
                task.session()::cancelActiveTransfers
        );
        updateDownload(true, false, true, task.modelName(), STATUS_CANCELLING_KEY, current.percent(), "");
        publishWaiting("tianshu.presence.module.llm.download_cancelling", "");
    }

    public boolean isDownloading() {
        return activeDownload.get() != null;
    }

    public DownloadSnapshot downloadSnapshot() {
        return downloadSnapshot.get();
    }

    public boolean deleteModel(LlmModelInfo info) {
        Path modelDir = resolveModelDir(info);
        if (modelDir == null || !Files.exists(modelDir)) return false;
        try {
            deleteRecursively(modelDir);
            return true;
        } catch (IOException e) {
            env.error("Failed to delete LLM model", e);
            return false;
        }
    }

    public void deleteModelAsync(LlmModelInfo info, ModelDeleteCallback callback) {
        if (info == null || info.name == null || info.name.isBlank()) {
            if (callback != null) callback.onComplete(false);
            return;
        }
        String modelName = info.name.trim();
        if (!deletingModelName.compareAndSet("", modelName)) {
            if (callback != null) callback.onComplete(false);
            return;
        }
        ProtocolTaskHandle handle = executorManager.submit(
                ProtocolTaskSpec.builder()
                        .moduleId("module.llm")
                        .lane(ExecutionLane.IO)
                        .concurrencyKey("module.llm:model.delete")
                        .maxConcurrency(1)
                        .queueCapacity(2)
                        .build(),
                () -> {
                    boolean deleted;
                    try {
                        deleted = deleteModel(info);
                    } finally {
                        deletingModelName.compareAndSet(modelName, "");
                    }
                    if (callback != null) {
                        callback.onComplete(deleted);
                    }
                }
        );
        if (handle.state() == ProtocolTaskState.REJECTED) {
            deletingModelName.compareAndSet(modelName, "");
            if (callback != null) callback.onComplete(false);
        }
    }

    public boolean isDeleting() {
        return !deletingModelName.get().isBlank();
    }

    public boolean isDeletingModel(LlmModelInfo info) {
        return info != null && info.name != null && info.name.equalsIgnoreCase(deletingModelName.get());
    }

    private void runDownload(DownloadTask task, LlmModelInfo info, DownloadProgressCallback callback) {
        DownloadProgressEmitter progressEmitter = new DownloadProgressEmitter(task, callback);
        try {
            task.session().download(info, task.modelDir(), new LlmModelDownloader.DownloadProgressCallback() {
                @Override
                public void onProgress(String label, int percent) {
                    progressEmitter.accept(label, percent);
                }

                @Override
                public void onComplete() {
                    finishDownloadComplete(task, callback);
                }

                @Override
                public void onError(String message) {
                    finishDownloadError(task, callback, message);
                }
            });
        } catch (Exception e) {
            if (task.session().isCancelled() || isDownloadCancelled(e)) {
                finishDownloadCancelled(task, callback);
            } else {
                finishDownloadError(task, callback, e.getMessage());
            }
        }
    }

    private Path modelBasePath() {
        return config.getLlmBasePath().resolve("model");
    }

    private void scheduleStartupCleanup() {
        executorManager.submit(
                ProtocolTaskSpec.builder()
                        .moduleId("module.llm")
                        .lane(ExecutionLane.IO)
                        .concurrencyKey("module.llm:model.cleanup")
                        .maxConcurrency(1)
                        .queueCapacity(1)
                        .build(),
                this::cleanupStaleIncompleteDownloads
        );
    }

    private void cleanupStaleIncompleteDownloads() {
        for (LlmModelInfo info : allModels()) {
            Path modelDir = resolveModelDir(info);
            if (modelDir == null || !isWithinModelBase(modelDir) || !Files.isDirectory(modelDir) || hasModelContent(info)) {
                continue;
            }
            try {
                deleteTemporaryDownloadFiles(modelDir);
                deleteDirectoryIfEmpty(modelDir);
            } catch (IOException e) {
                env.warn("Failed to clean incomplete LLM model download: " + modelDir + " - " + e.getMessage());
            }
        }
    }

    private void deleteTemporaryDownloadFiles(Path modelDir) throws IOException {
        try (var stream = Files.walk(modelDir)) {
            for (Path path : stream.filter(Files::isRegularFile).toList()) {
                String fileName = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase();
                if (fileName.endsWith(".tmp") || fileName.endsWith(".downloading")) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private void deleteDirectoryIfEmpty(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var stream = Files.list(directory)) {
            if (stream.findAny().isEmpty()) {
                Files.deleteIfExists(directory);
            }
        }
    }

    private void updateDownload(boolean running, boolean paused, boolean cancelling, String modelName, String label, int percent, String errorMessage) {
        downloadSnapshot.set(new DownloadSnapshot(running, paused, cancelling, modelName, label, percent, errorMessage, System.currentTimeMillis()));
    }

    private boolean isCurrentTask(DownloadTask task) {
        return task != null && activeDownload.get() == task;
    }

    private boolean finishTask(DownloadTask task) {
        return task != null && activeDownload.compareAndSet(task, null);
    }

    private void finishDownloadComplete(DownloadTask task, DownloadProgressCallback callback) {
        if (!finishTask(task)) {
            return;
        }
        updateDownload(false, false, false, task.modelName(), STATUS_DOWNLOAD_COMPLETE_KEY, 100, "");
        publishReady("tianshu.presence.module.llm.download_complete", "");
        if (callback != null) callback.onComplete();
    }

    private void finishDownloadCancelled(DownloadTask task, DownloadProgressCallback callback) {
        if (!finishTask(task)) {
            return;
        }
        cleanupCancelledDownload(task);
        updateDownload(false, false, false, task.modelName(), STATUS_CANCELLED_KEY, downloadSnapshot.get().percent(), "");
        publishReady("tianshu.presence.module.llm.download_cancelled", "");
        if (callback != null) callback.onCancelled();
    }

    private void finishDownloadError(DownloadTask task, DownloadProgressCallback callback, String message) {
        if (!finishTask(task)) {
            return;
        }
        if (message != null && !message.isBlank()) {
            env.warn("LLM model download failed: " + message);
        }
        updateDownload(false, false, false, task.modelName(), STATUS_DOWNLOAD_FAILED_KEY, downloadSnapshot.get().percent(), ERROR_DOWNLOAD_FAILED_KEY);
        publishFailed("tianshu.presence.module.llm.download_failed", "");
        if (callback != null) callback.onError(ERROR_DOWNLOAD_FAILED_KEY);
    }

    private void publishReady(String messageKey, String fallbackTitle) {
        moduleStatusSink.accept(ModuleStatuses.readyKeyed("module.llm", messageKey, fallbackTitle));
    }

    private void publishWaiting(String messageKey, String fallbackTitle) {
        moduleStatusSink.accept(ModuleStatuses.waitingKeyed("module.llm", messageKey, fallbackTitle));
    }

    private void publishFailed(String messageKey, String fallbackTitle) {
        moduleStatusSink.accept(ModuleStatuses.failedKeyed("module.llm", messageKey, fallbackTitle));
    }

    private void cleanupCancelledDownload(DownloadTask task) {
        if (task == null || task.hadModelContent()) {
            return;
        }
        Path modelDir = task.modelDir();
        if (modelDir == null || !isWithinModelBase(modelDir)) {
            return;
        }
        try {
            deleteRecursivelyIfExists(modelDir);
        } catch (IOException e) {
            env.warn("Failed to clean cancelled LLM model directory: " + modelDir + " - " + e.getMessage());
        }
    }

    private boolean isWithinModelBase(Path path) {
        if (path == null) {
            return false;
        }
        return path.toAbsolutePath().normalize().startsWith(modelBasePath().toAbsolutePath().normalize());
    }

    private boolean isDownloadCancelled(Exception exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof LlmModelDownloader.DownloadCancelledException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void deleteRecursivelyIfExists(Path path) throws IOException {
        if (path != null && Files.exists(path)) {
            deleteRecursively(path);
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                for (Path child : stream.toList()) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    private record DownloadTask(
            String modelName,
            Path modelDir,
            boolean hadModelContent,
            LlmModelDownloadCoordinator.DownloadSession session
    ) {
        private DownloadTask {
            modelName = modelName == null ? "" : modelName.trim();
        }
    }

    private final class DownloadProgressEmitter {
        private final DownloadTask task;
        private final DownloadProgressCallback callback;
        private String lastLabel = "";
        private int lastPercent = -1;
        private long lastEmittedAtMillis;

        private DownloadProgressEmitter(DownloadTask task, DownloadProgressCallback callback) {
            this.task = task;
            this.callback = callback;
        }

        private void accept(String label, int percent) {
            if (!isCurrentTask(task) || task.session().isCancelled()) {
                return;
            }
            String safeLabel = STATUS_DOWNLOADING_KEY;
            int safePercent = Math.max(0, Math.min(100, percent));
            long now = System.currentTimeMillis();
            boolean changed = safePercent != lastPercent || !safeLabel.equals(lastLabel);
            boolean shouldEmit = changed && (lastPercent < 0 || safePercent >= 100 || now - lastEmittedAtMillis >= PROGRESS_UPDATE_INTERVAL_MILLIS);
            if (!shouldEmit) {
                return;
            }
            lastLabel = safeLabel;
            lastPercent = safePercent;
            lastEmittedAtMillis = now;
            updateDownload(true, task.session().isPaused(), false, task.modelName(), safeLabel, safePercent, "");
            if (callback != null) {
                callback.onProgress(safeLabel, safePercent);
            }
        }
    }
}


