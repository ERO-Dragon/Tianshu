package com.rheinmetal.tianshu.function.llm.download;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.model.LlmModelDownloader;
import com.rheinmetal.tianshu.model.LlmModelInfo;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LlmModelDownloadCoordinator {
    private final IGameEnvironment env;

    public LlmModelDownloadCoordinator(IGameEnvironment env) {
        this.env = Objects.requireNonNull(env, "env");
    }

    public DownloadSession newSession() {
        return new DownloadSession(new LlmModelDownloader(env));
    }

    public static final class DownloadSession {
        private final LlmModelDownloader downloader;
        private final AtomicBoolean paused = new AtomicBoolean(false);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile boolean cancelNotified;

        private DownloadSession(LlmModelDownloader downloader) {
            this.downloader = downloader;
        }

        public boolean isPaused() {
            return paused.get();
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        public void pause() {
            if (!cancelled.get()) {
                paused.set(true);
            }
        }

        public void resume() {
            if (!cancelled.get()) {
                paused.set(false);
            }
        }

        public void cancel() {
            cancelled.set(true);
            paused.set(false);
        }

        public void cancelActiveTransfers() {
            if (!cancelNotified) {
                cancelNotified = true;
                downloader.cancelActiveTransfers();
            }
        }

        public void download(LlmModelInfo info, Path targetDir, LlmModelDownloader.DownloadProgressCallback callback) throws Exception {
            downloader.downloadSync(info, targetDir, callback, this::awaitReady);
        }

        private void awaitReady() throws IOException {
            if (cancelled.get()) {
                throw new LlmModelDownloader.DownloadCancelledException();
            }
            while (paused.get()) {
                if (cancelled.get()) {
                    throw new LlmModelDownloader.DownloadCancelledException();
                }
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Download thread was interrupted", e);
                }
            }
            if (cancelled.get()) {
                throw new LlmModelDownloader.DownloadCancelledException();
            }
        }
    }
}
