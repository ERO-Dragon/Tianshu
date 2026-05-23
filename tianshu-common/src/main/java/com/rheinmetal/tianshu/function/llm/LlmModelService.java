package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.model.LlmModelDownloader;
import com.rheinmetal.tianshu.model.LlmModelInfo;
import com.rheinmetal.tianshu.model.LlmModelManager;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutorManager;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class LlmModelService {
    public interface DownloadProgressCallback {
        void onProgress(String label, int percent);
        void onComplete();
        void onError(String message);
    }

    public record DownloadSnapshot(boolean running, String modelName, String label, int percent, String errorMessage, long updatedAtMillis) {
        public DownloadSnapshot {
            modelName = modelName == null ? "" : modelName.trim();
            label = label == null ? "" : label.trim();
            errorMessage = errorMessage == null ? "" : errorMessage.trim();
            percent = Math.max(0, Math.min(100, percent));
            updatedAtMillis = updatedAtMillis > 0L ? updatedAtMillis : System.currentTimeMillis();
        }

        public static DownloadSnapshot idle() {
            return new DownloadSnapshot(false, "", "空闲", 0, "", System.currentTimeMillis());
        }
    }

    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final ProtocolExecutorManager executorManager;
    private final AtomicBoolean downloading = new AtomicBoolean(false);
    private final AtomicReference<DownloadSnapshot> downloadSnapshot = new AtomicReference<>(DownloadSnapshot.idle());
    private volatile LlmModelDownloader activeDownloader;
    private volatile ProtocolTaskHandle activeDownloadTask;

    public LlmModelService(IGameEnvironment env, ITianshuConfig config, ProtocolExecutorManager executorManager) {
        this.env = Objects.requireNonNull(env, "env");
        this.config = Objects.requireNonNull(config, "config");
        this.executorManager = Objects.requireNonNull(executorManager, "executorManager");
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
            if (callback != null) callback.onError("LLM 模型信息为空");
            return;
        }
        if (!downloading.compareAndSet(false, true)) {
            if (callback != null) callback.onError("已有 LLM 模型正在下载");
            return;
        }
        updateDownload(true, info.name, "下载中", 0, "");
        activeDownloadTask = executorManager.submit(
                ProtocolTaskSpec.builder()
                        .moduleId("module.llm")
                        .lane(ExecutionLane.IO)
                        .concurrencyKey("module.llm:model.download")
                        .maxConcurrency(1)
                        .queueCapacity(1)
                        .build(),
                () -> runDownload(info, callback)
        );
    }

    public void cancelDownload() {
        LlmModelDownloader downloader = activeDownloader;
        if (downloader != null) {
            downloader.cancelDownload();
        }
        ProtocolTaskHandle task = activeDownloadTask;
        if (task != null && !task.isDone()) {
            task.cancel("llm model download cancelled");
        }
        downloading.set(false);
        updateDownload(false, downloadSnapshot.get().modelName(), "下载已取消", downloadSnapshot.get().percent(), "");
    }

    public boolean isDownloading() {
        return downloading.get();
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
            env.error("删除 LLM 模型失败", e);
            return false;
        }
    }

    private void runDownload(LlmModelInfo info, DownloadProgressCallback callback) {
        LlmModelDownloader downloader = new LlmModelDownloader(env);
        activeDownloader = downloader;
        try {
            downloader.download(info, resolveModelDir(info), new LlmModelDownloader.DownloadProgressCallback() {
                @Override
                public void onProgress(String label, int percent) {
                    updateDownload(true, info.name, label, percent, "");
                    if (callback != null) callback.onProgress(label, percent);
                }

                @Override
                public void onComplete() {
                    downloading.set(false);
                    updateDownload(false, info.name, "下载完成", 100, "");
                    if (callback != null) callback.onComplete();
                }

                @Override
                public void onError(String message) {
                    downloading.set(false);
                    updateDownload(false, info.name, "下载失败", downloadSnapshot.get().percent(), message);
                    if (callback != null) callback.onError(message);
                }
            });
        } finally {
            activeDownloader = null;
            activeDownloadTask = null;
        }
    }

    private Path modelBasePath() {
        return config.getLlmBasePath().resolve("model");
    }

    private void updateDownload(boolean running, String modelName, String label, int percent, String errorMessage) {
        downloadSnapshot.set(new DownloadSnapshot(running, modelName, label, percent, errorMessage, System.currentTimeMillis()));
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
}
