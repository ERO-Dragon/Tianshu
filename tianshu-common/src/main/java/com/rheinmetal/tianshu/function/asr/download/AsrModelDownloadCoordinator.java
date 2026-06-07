package com.rheinmetal.tianshu.function.asr.download;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.model.AsrModelDownloader;
import com.rheinmetal.tianshu.model.AsrModelInfo;

import java.nio.file.Path;

public final class AsrModelDownloadCoordinator {
    private final AsrModelDownloader downloader;

    public AsrModelDownloadCoordinator(IGameEnvironment env) {
        this.downloader = new AsrModelDownloader(env);
    }

    public boolean isPaused() {
        return downloader.isDownloadPaused();
    }

    public void pause() {
        downloader.pauseDownload();
    }

    public void resume() {
        downloader.resumeDownload();
    }

    public void cancel() {
        downloader.cancelDownload();
    }

    public void download(AsrModelInfo info, Path targetDir, String githubProxyUrl, AsrModelDownloader.DownloadProgressCallback callback) throws Exception {
        downloader.downloadSync(info, targetDir, githubProxyUrl, callback);
    }
}
