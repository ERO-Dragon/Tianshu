package com.rheinmetal.tianshu.function.llm.download;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.model.LlmModelDownloader;
import com.rheinmetal.tianshu.model.LlmModelInfo;
import com.rheinmetal.tianshu.model.download.DownloadGate;
import com.rheinmetal.tianshu.model.lifecycle.ModelDownloadCallback;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

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
        private final DownloadGate gate;

        private DownloadSession(LlmModelDownloader downloader) {
            this.downloader = downloader;
            this.gate = new DownloadGate(downloader::cancelActiveTransfers);
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

        public void download(LlmModelInfo info, Path targetDir, ModelDownloadCallback callback) throws Exception {
            downloader.downloadSync(info, targetDir, callback, this::awaitReady);
        }

        void awaitReady() throws IOException {
            try {
                gate.awaitReady();
            } catch (DownloadGate.CancelledException cancelled) {
                throw new LlmModelDownloader.DownloadCancelledException();
            }
        }
    }
}
