package com.rheinmetal.tianshu.function.tts.download;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.model.HuggingFaceDownloader;
import com.rheinmetal.tianshu.model.ModelArchiveDownloader;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TtsModelDownloadCoordinator {
    private final IGameEnvironment env;

    public TtsModelDownloadCoordinator(IGameEnvironment env) {
        this.env = Objects.requireNonNull(env, "env");
    }

    public DownloadSession newSession() {
        return new DownloadSession(new HuggingFaceDownloader(env), new ModelArchiveDownloader(env));
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
        private final HuggingFaceDownloader hfDownloader;
        private final ModelArchiveDownloader archiveDownloader;
        private final Object pauseMonitor = new Object();
        private final AtomicBoolean paused = new AtomicBoolean(false);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        private DownloadSession(HuggingFaceDownloader hfDownloader, ModelArchiveDownloader archiveDownloader) {
            this.hfDownloader = hfDownloader;
            this.archiveDownloader = archiveDownloader;
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
            archiveDownloader.cancelActiveTransfers();
        }

        public void downloadModelFiles(String repoId, Path targetDir, String revision, boolean skipExisting, int maxRetries, HuggingFaceDownloader.DownloadProgressListener progress) throws Exception {
            hfDownloader.downloadModelFiles(repoId, targetDir, revision, skipExisting, maxRetries, this::awaitReady, progress);
        }

        public void downloadArchive(
                URI directUri,
                URI proxyBaseUri,
                boolean proxyFirst,
                Path targetPath,
                int maxRetries,
                int timeoutMillis,
                ArchiveProgressListener listener
        ) throws IOException {
            archiveDownloader.downloadGithubArchive(
                    directUri,
                    proxyBaseUri,
                    proxyFirst,
                    targetPath,
                    new ModelArchiveDownloader.RetryPolicy(maxRetries, timeoutMillis, timeoutMillis, 0L),
                    this::awaitReady,
                    listener == null ? null : listener::onProgress
            );
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
