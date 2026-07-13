package com.rheinmetal.tianshu.function.tts.download;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.model.HuggingFaceDownloader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TtsModelDownloadCoordinator {
    private final IGameEnvironment env;

    public TtsModelDownloadCoordinator(IGameEnvironment env) {
        this.env = Objects.requireNonNull(env, "env");
    }

    public DownloadSession newSession() {
        return new DownloadSession(env, new HuggingFaceDownloader(env));
    }

    public static final class DownloadCancelledException extends IOException {
        public DownloadCancelledException() {
            super("Download was cancelled");
        }
    }

    public interface ArchiveProgressListener {
        void onProgress(long downloaded, long total);
    }

    public static final class DownloadSession {
        private final IGameEnvironment env;
        private final HuggingFaceDownloader hfDownloader;
        private final Set<HttpURLConnection> activeConnections = ConcurrentHashMap.newKeySet();
        private final Object pauseMonitor = new Object();
        private final AtomicBoolean paused = new AtomicBoolean(false);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        private DownloadSession(IGameEnvironment env, HuggingFaceDownloader hfDownloader) {
            this.env = env;
            this.hfDownloader = hfDownloader;
        }

        public boolean isPaused() {
            return paused.get();
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        public void pause() {
            synchronized (pauseMonitor) {
                if (!cancelled.get()) {
                    paused.set(true);
                }
            }
        }

        public void resume() {
            synchronized (pauseMonitor) {
                if (!cancelled.get()) {
                    paused.set(false);
                }
                pauseMonitor.notifyAll();
            }
        }

        public void cancel() {
            synchronized (pauseMonitor) {
                cancelled.set(true);
                paused.set(false);
                pauseMonitor.notifyAll();
            }
            cancelActiveTransfers();
        }

        public void cancelActiveTransfers() {
            hfDownloader.cancelActiveTransfers();
            for (HttpURLConnection connection : activeConnections) {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }

        public void downloadModelFiles(String repoId, Path targetDir, String revision, boolean skipExisting, int maxRetries, HuggingFaceDownloader.DownloadProgressListener progress) throws Exception {
            hfDownloader.downloadModelFiles(repoId, targetDir, revision, skipExisting, maxRetries, this::awaitReady, progress);
        }

        public void downloadArchive(String urlString, Path targetPath, int maxRetries, int timeoutMillis, ArchiveProgressListener listener) throws IOException {
            IOException last = null;
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                awaitReady();
                HttpURLConnection conn = null;
                Path tempPath = targetPath.resolveSibling(targetPath.getFileName() + ".downloading");
                try {
                    Files.createDirectories(targetPath.getParent());
                    conn = (HttpURLConnection) new URL(urlString).openConnection();
                    activeConnections.add(conn);
                    conn.setConnectTimeout(timeoutMillis);
                    conn.setReadTimeout(timeoutMillis);
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("User-Agent", "Tianshu-Downloader/1.0");
                    int code = conn.getResponseCode();
                    if (code != 200) {
                        throw new IOException("HTTP request failed with status " + code);
                    }
                    long total = conn.getContentLengthLong();
                    long downloaded = 0L;
                    try (InputStream in = conn.getInputStream();
                         OutputStream out = Files.newOutputStream(tempPath)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            awaitReady();
                            out.write(buffer, 0, read);
                            downloaded += read;
                            if (listener != null) {
                                listener.onProgress(downloaded, total);
                            }
                        }
                    }
                    Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    return;
                } catch (IOException e) {
                    last = e;
                    Files.deleteIfExists(tempPath);
                    awaitReady();
                    env.warn("TTS archive download retry " + attempt + "/" + maxRetries + ": " + targetPath.getFileName() + " - " + e.getMessage());
                } finally {
                    if (conn != null) {
                        activeConnections.remove(conn);
                        conn.disconnect();
                    }
                }
            }
            throw last != null ? last : new IOException("Archive download failed");
        }

        public void awaitReady() throws IOException {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                throw new DownloadCancelledException();
            }
            synchronized (pauseMonitor) {
                while (paused.get() && !cancelled.get()) {
                    try {
                        pauseMonitor.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Download thread was interrupted", e);
                    }
                }
            }
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                throw new DownloadCancelledException();
            }
        }
    }
}
