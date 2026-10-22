package com.rheinmetal.tianshu.function.intake.download;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.model.AsrModelDownloader;
import com.rheinmetal.tianshu.model.AsrModelInfo;
import com.rheinmetal.tianshu.model.download.DownloadGate;
import com.rheinmetal.tianshu.model.lifecycle.ModelDownloadCallback;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

public final class AsrModelDownloadCoordinator {
    private final IGameEnvironment env;

    public AsrModelDownloadCoordinator(IGameEnvironment env) {
        this.env = Objects.requireNonNull(env, "env");
    }

    public DownloadSession newSession() {
        return new DownloadSession(new AsrModelDownloader(env));
    }

    public static final class DownloadSession {
        private final AsrModelDownloader downloader;
        private final DownloadGate gate;

        private DownloadSession(AsrModelDownloader downloader) {
            this.downloader = downloader;
            this.gate = new DownloadGate(downloader::cancelActiveTransfers);
        }

        public boolean isPaused() {
            return gate.isPaused();
        }

        public boolean isCancelled() {
            return gate.isCancelled();
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

        public void download(AsrModelInfo info, Path targetDir, String githubProxyUrl, ModelDownloadCallback callback) throws Exception {
            downloader.downloadSync(info, targetDir, githubProxyUrl, callback, this::awaitReady);
        }

        void awaitReady() throws IOException {
            try {
                gate.awaitReady();
            } catch (DownloadGate.CancelledException cancelled) {
                throw new AsrModelDownloader.DownloadCancelledException();
            }
        }
    }
}
