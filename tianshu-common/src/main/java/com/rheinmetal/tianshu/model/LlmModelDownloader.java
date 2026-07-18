package com.rheinmetal.tianshu.model;

import com.rheinmetal.tianshu.api.IGameEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public class LlmModelDownloader {

    public interface DownloadProgressCallback {
        void onProgress(ModelDownloadProgress progress);
        void onComplete();
        void onError(String message);
    }

    public interface DownloadControl {
        void awaitReady() throws IOException;
    }

    public static final class DownloadCancelledException extends IOException {
        public DownloadCancelledException() {
            super("Download was cancelled");
        }
    }

    private final IGameEnvironment env;
    private final HuggingFaceDownloader hfDownloader;
    private volatile boolean cancelled = false;
    private volatile boolean paused = false;

    public LlmModelDownloader(IGameEnvironment env) {
        this.env = env;
        this.hfDownloader = new HuggingFaceDownloader(env);
    }

    public void cancelDownload() {
        cancelled = true;
        paused = false;
        hfDownloader.cancelActiveTransfers();
    }

    public void cancelActiveTransfers() {
        hfDownloader.cancelActiveTransfers();
    }

    public void pauseDownload() {
        if (!cancelled) {
            paused = true;
        }
    }

    public void resumeDownload() {
        if (!cancelled) {
            paused = false;
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public boolean isPaused() {
        return paused;
    }

    public void download(LlmModelInfo info, Path targetDir, DownloadProgressCallback callback) {
        Objects.requireNonNull(callback, "callback");
        cancelled = false;
        paused = false;
        try {
            downloadSync(info, targetDir, callback, this::awaitReady);
        } catch (Exception e) {
            callback.onError("tianshu.gui.llm.error.download_failed");
        }
    }

    public void downloadSync(LlmModelInfo info, Path targetDir, DownloadProgressCallback callback, DownloadControl control) throws Exception {
        Objects.requireNonNull(info, "info");
        Objects.requireNonNull(targetDir, "targetDir");
        Objects.requireNonNull(callback, "callback");
        doDownload(info, targetDir, callback, control == null ? () -> {} : control);
    }

    private void doDownload(LlmModelInfo info, Path targetDir, DownloadProgressCallback callback, DownloadControl control) throws Exception {
        Path targetFile = targetDir.resolve(info.getModelFile());
        control.awaitReady();

        if (Files.exists(targetFile) && Files.size(targetFile) > 0) {
            env.info("LLM 模型已存在，跳过下载: " + targetFile);
            callback.onProgress(ModelDownloadProgress.stage(ModelDownloadStage.COMPLETED, 100, "model.already_present"));
            callback.onComplete();
            return;
        }

        if (info.repoId == null || info.repoId.isBlank()) {
            throw new IllegalStateException("模型缺少 repoId: " + info.name);
        }

        String filePath = resolveHfFilePath(info);
        env.info("LLM 模型下载: repo=" + info.repoId + " file=" + filePath + " → " + targetFile);

        control.awaitReady();
        callback.onProgress(ModelDownloadProgress.stage(ModelDownloadStage.DOWNLOADING, 5, "model.file.download"));
        hfDownloader.downloadSingleFile(info.repoId, filePath, targetFile, "main", 3, control::awaitReady, new HuggingFaceDownloader.DownloadProgressListener() {
            @Override
            public void onFileProgress(String filePath, int fileIndex, int totalFiles, long downloadedBytes, long totalBytes) {
                if (totalBytes > 0L) {
                    int percent = 5 + (int) Math.min(90L, downloadedBytes * 90L / totalBytes);
                    callback.onProgress(ModelDownloadProgress.bytes(ModelDownloadStage.DOWNLOADING, percent, downloadedBytes, totalBytes, "model.file.download"));
                }
            }
        });

        control.awaitReady();
        if (!Files.exists(targetFile) || Files.size(targetFile) == 0) {
            throw new IllegalStateException("下载完成但文件不存在或为空: " + targetFile);
        }

        callback.onProgress(ModelDownloadProgress.stage(ModelDownloadStage.COMPLETED, 100, "download.completed"));
        callback.onComplete();
    }

    private String resolveHfFilePath(LlmModelInfo info) {
        if (info.hfFilePath != null && !info.hfFilePath.isBlank()) {
            return info.hfFilePath;
        }
        return info.getModelFile();
    }

    private void awaitReady() throws IOException {
        if (cancelled || Thread.currentThread().isInterrupted()) {
            throw new DownloadCancelledException();
        }
        while (paused) {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                paused = false;
                throw new DownloadCancelledException();
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("下载线程被中断", e);
            }
        }
    }
}
