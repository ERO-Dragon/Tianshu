package com.rheinmetal.tianshu.function.weaving.model;

import com.rheinmetal.tianshu.model.HuggingFaceDownloader;
import com.rheinmetal.tianshu.model.ModelDownloadProgress;
import com.rheinmetal.tianshu.model.ModelDownloadStage;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.model.lifecycle.ModelDownloadCallback;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.nio.file.AccessDeniedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class LlmModelDownloader {

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
        this(env, new HuggingFaceDownloader(env));
    }

    LlmModelDownloader(IGameEnvironment env, HuggingFaceDownloader hfDownloader) {
        this.env = env;
        this.hfDownloader = Objects.requireNonNull(hfDownloader, "hfDownloader");
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

    public void download(LlmModelInfo info, Path targetDir, ModelDownloadCallback callback) {
        Objects.requireNonNull(callback, "callback");
        cancelled = false;
        paused = false;
        try {
            downloadSync(info, targetDir, callback, this::awaitReady);
        } catch (DownloadCancelledException cancelledDownload) {
            callback.onError("tianshu.gui.llm.error.download_failed");
        } catch (Exception failure) {
            callback.onError(failureDiagnostic(failure));
        }
    }

    /**
     * Turns the download failure into the one line the status row shows: which source failed and why.
     *
     * <p>The transport aggregates one exception per candidate and keeps each attempt as a suppressed
     * failure, so the reason is already in the exception tree — a socket timeout, a refused connect, an
     * HTTP status or a disk error. Reporting only "download failed" is what made a mirror-wide outage
     * indistinguishable from a typo in a repository id.
     */
    static String failureDiagnostic(Throwable failure) {
        List<String> entries = new ArrayList<>();
        for (Throwable suppressed : failure.getSuppressed()) {
            String source = sourceOf(suppressed);
            if (source.isEmpty()) {
                continue;
            }
            String entry = source + ": " + reasonOf(suppressed);
            if (!entries.contains(entry)) {
                entries.add(entry);
            }
        }
        if (entries.isEmpty()) {
            return "download failed: " + reasonOf(failure);
        }
        return String.join("; ", entries);
    }

    private static String sourceOf(Throwable failure) {
        String message = failure.getMessage();
        if (message == null) {
            return "";
        }
        int index = message.indexOf("source=");
        if (index < 0) {
            return "";
        }
        String source = message.substring(index + "source=".length()).trim();
        int end = source.indexOf(' ');
        if (end > 0) {
            source = source.substring(0, end);
        }
        try {
            String host = URI.create(source).getHost();
            return host == null || host.isBlank() ? source : host;
        } catch (RuntimeException notAUrl) {
            return source;
        }
    }

    private static String reasonOf(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof java.net.SocketTimeoutException) {
                return "timeout";
            }
            if (current instanceof java.net.ConnectException || current instanceof java.net.NoRouteToHostException) {
                return "unreachable";
            }
            if (current instanceof java.net.UnknownHostException) {
                return "unknown-host";
            }
            if (current instanceof javax.net.ssl.SSLException) {
                return "tls-blocked";
            }
            if (current instanceof java.nio.file.AccessDeniedException) {
                return "disk-denied";
            }
            if (current instanceof java.io.IOException && current.getMessage() != null) {
                String message = current.getMessage();
                int statusIndex = message.indexOf("status=");
                if (statusIndex >= 0) {
                    String status = message.substring(statusIndex + "status=".length()).trim();
                    int end = status.indexOf(' ');
                    if (end > 0) {
                        status = status.substring(0, end);
                    }
                    return "http-" + status;
                }
            }
            if (current.getClass().getSimpleName().contains("Disk")) {
                return "disk-" + current.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
            }
            current = current.getCause();
        }
        return "failed";
    }

    public void downloadSync(LlmModelInfo info, Path targetDir, ModelDownloadCallback callback, DownloadControl control) throws Exception {
        Objects.requireNonNull(info, "info");
        Objects.requireNonNull(targetDir, "targetDir");
        Objects.requireNonNull(callback, "callback");
        doDownload(info, targetDir, callback, control == null ? () -> {} : control);
    }

    private void doDownload(LlmModelInfo info, Path targetDir, ModelDownloadCallback callback, DownloadControl control) throws Exception {
        Path targetFile = targetDir.resolve(info.getModelFile());
        control.awaitReady();

        if (Files.exists(targetFile) && Files.size(targetFile) > 0) {
            env.info("LLM model already present, skipping download: " + targetFile);
            callback.onProgress(ModelDownloadProgress.stage(ModelDownloadStage.COMPLETED, 100, "model.already_present"));
            callback.onComplete();
            return;
        }

        if (info.repoId == null || info.repoId.isBlank()) {
            throw new IllegalStateException("model is missing repoId: " + info.name);
        }

        String filePath = resolveHfFilePath(info);
        env.info("LLM model download: repo=" + info.repoId + " file=" + filePath + " → " + targetFile);

        control.awaitReady();
        callback.onProgress(ModelDownloadProgress.stage(ModelDownloadStage.DOWNLOADING, 0, "model.file.download"));
        hfDownloader.downloadSingleFile(info.repoId, filePath, targetFile, "main", 3, control::awaitReady, new HuggingFaceDownloader.DownloadProgressListener() {
            @Override
            public void onFileProgress(String filePath, int fileIndex, int totalFiles, long downloadedBytes, long totalBytes) {
                long effectiveTotalBytes = totalBytes > 0L ? totalBytes : info.getDownloadSizeBytes();
                int percent = effectiveTotalBytes > 0L
                        ? (int) Math.min(100L, downloadedBytes * 100L / effectiveTotalBytes)
                        : 0;
                callback.onProgress(ModelDownloadProgress.bytes(
                        ModelDownloadStage.DOWNLOADING,
                        percent,
                        downloadedBytes,
                        effectiveTotalBytes,
                        "model.file.download"
                ));
            }
        });

        control.awaitReady();
        if (!Files.exists(targetFile) || Files.size(targetFile) == 0) {
            throw new IllegalStateException("download finished but the file is missing or empty: " + targetFile);
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
                throw new IOException("download thread was interrupted", e);
            }
        }
    }
}
