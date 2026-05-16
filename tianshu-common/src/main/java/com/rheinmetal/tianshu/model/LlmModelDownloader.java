package com.rheinmetal.tianshu.model;

import com.rheinmetal.tianshu.api.IGameEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public class LlmModelDownloader {

    public interface DownloadProgressCallback {
        void onProgress(String label, int percent);
        void onComplete();
        void onError(String message);
    }

    private final IGameEnvironment env;
    private final HuggingFaceDownloader hfDownloader;

    public LlmModelDownloader(IGameEnvironment env) {
        this.env = env;
        this.hfDownloader = new HuggingFaceDownloader(env);
    }

    public void download(LlmModelInfo info, Path targetDir, DownloadProgressCallback callback) {
        Objects.requireNonNull(info, "info");
        Objects.requireNonNull(targetDir, "targetDir");
        Objects.requireNonNull(callback, "callback");

        try {
            doDownload(info, targetDir, callback);
        } catch (Exception e) {
            callback.onError(e.getMessage() != null ? e.getMessage() : "LLM 模型下载失败");
        }
    }

    private void doDownload(LlmModelInfo info, Path targetDir, DownloadProgressCallback callback) throws Exception {
        Path targetFile = targetDir.resolve(info.getModelFile());

        if (Files.exists(targetFile) && Files.size(targetFile) > 0) {
            env.info("LLM 模型已存在，跳过下载: " + targetFile);
            callback.onProgress("已存在", 100);
            callback.onComplete();
            return;
        }

        if (info.repoId == null || info.repoId.isBlank()) {
            throw new IllegalStateException("模型缺少 repoId: " + info.name);
        }

        String filePath = resolveHfFilePath(info);
        env.info("LLM 模型下载: repo=" + info.repoId + " file=" + filePath + " → " + targetFile);

        callback.onProgress("下载中", 5);
        hfDownloader.downloadSingleFile(info.repoId, filePath, targetFile, "main", 3);

        if (!Files.exists(targetFile) || Files.size(targetFile) == 0) {
            throw new IllegalStateException("下载完成但文件不存在或为空: " + targetFile);
        }

        callback.onProgress("完成", 100);
        callback.onComplete();
    }

    private String resolveHfFilePath(LlmModelInfo info) {
        if (info.hfFilePath != null && !info.hfFilePath.isBlank()) {
            return info.hfFilePath;
        }
        return info.getModelFile();
    }
}
