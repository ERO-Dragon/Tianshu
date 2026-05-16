package com.rheinmetal.tianshu.function.asr.download;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.model.AsrModelDownloader;
import com.rheinmetal.tianshu.model.AsrModelInfo;
import com.rheinmetal.tianshu.model.HuggingFaceDownloader;

import java.nio.file.Path;

public final class AsrModelDownloadCoordinator {
    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final AsrModelDownloader standardDownloader;
    private volatile boolean paused;
    private volatile boolean cancelled;

    public AsrModelDownloadCoordinator(IGameEnvironment env, ITianshuConfig config) {
        this.env = env;
        this.config = config;
        this.standardDownloader = new AsrModelDownloader(env);
    }

    public boolean isPaused() {
        return paused || standardDownloader.isDownloadPaused();
    }

    public void pause() {
        paused = true;
        standardDownloader.pauseDownload();
    }

    public void resume() {
        paused = false;
        standardDownloader.resumeDownload();
    }

    public void cancel() {
        cancelled = true;
        paused = false;
        standardDownloader.cancelDownload();
    }

    public void download(AsrModelInfo info, Path targetDir, String githubProxyUrl, AsrModelDownloader.DownloadProgressCallback callback) throws Exception {
        resetControlState();
        try {
            standardDownloader.downloadSync(info, targetDir, githubProxyUrl, callback);
        } catch (Exception standardFailure) {
            if (cancelled || !canFallbackToSnapshot(info)) {
                throw standardFailure;
            }
            env.warn("ASR 标准下载失败，尝试 HuggingFace 快照回退: " + standardFailure.getMessage());
            callback.onProgress("标准下载失败，尝试备用链路", 5);
            downloadSnapshotFallback(info, targetDir, callback);
            callback.onProgress("完成", 100);
            callback.onComplete();
        }
    }

    private void downloadSnapshotFallback(AsrModelInfo info, Path targetDir, AsrModelDownloader.DownloadProgressCallback callback) throws Exception {
        checkControlState();
        HuggingFaceDownloader downloader = new HuggingFaceDownloader(env);
        callback.onProgress("备用链路下载中", 10);
        downloader.downloadModelFiles(info.id, targetDir, "main", true, 3);
        checkControlState();
    }

    private boolean canFallbackToSnapshot(AsrModelInfo info) {
        return info != null && info.id != null && !info.id.isBlank();
    }

    private void resetControlState() {
        paused = false;
        cancelled = false;
        standardDownloader.resumeDownload();
    }

    private void checkControlState() {
        while (paused) {
            if (cancelled) {
                throw new RuntimeException("下载已取消");
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("下载线程被中断", e);
            }
        }
        if (cancelled) {
            throw new RuntimeException("下载已取消");
        }
    }
}
