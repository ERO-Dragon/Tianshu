package com.rheinmetal.tianshu.function.echo.download;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.model.HuggingFaceDownloader;
import com.rheinmetal.tianshu.model.ModelArchiveDownloader;
import com.rheinmetal.tianshu.model.download.DownloadGate;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

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
        private final DownloadGate gate;

        private DownloadSession(HuggingFaceDownloader hfDownloader, ModelArchiveDownloader archiveDownloader) {
            this.hfDownloader = hfDownloader;
            this.archiveDownloader = archiveDownloader;
            this.gate = new DownloadGate(() -> {
                hfDownloader.cancelActiveTransfers();
                archiveDownloader.cancelActiveTransfers();
            });
        }

        public boolean isPaused() {
            return gate.isPaused();
        }

        public boolean isCancelled() {
            return gate.isCancelled();
        }

        public boolean shouldKeepPartialDownload() {
            return gate.shouldKeepPartialDownload();
        }

        public void pause() {
            gate.pause();
        }

        public void resume() {
            gate.resume();
        }

        public void cancel() {
            gate.cancel();
        }

        public void cancelKeepingPartial() {
            gate.cancelKeepingPartial();
        }

        public void cancelActiveTransfers() {
            gate.cancelActiveTransfers();
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
            try {
                gate.awaitReady();
            } catch (DownloadGate.CancelledException cancelled) {
                throw new DownloadCancelledException();
            }
        }
    }
}
