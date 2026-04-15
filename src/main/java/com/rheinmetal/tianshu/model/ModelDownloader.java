package com.rheinmetal.tianshu.model;

import com.rheinmetal.tianshu.Tianshu;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

public class ModelDownloader {

    // 进度回调接口
    public interface DownloadCallback {
        void onProgress(long downloadedBytes, long totalBytes);
        void onSuccess(Path savedPath);
        void onError(String errorMessage);
    }

    private static final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS) // 强制跟随重定向（很多模型下载链是重定向的
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * 异步下载模型文件
     * @param url 下载链接
     * @param targetPath 保存的绝对路径
     * @param callback 进度回调
     */
    public static void downloadAsync(String url, Path targetPath, DownloadCallback callback) {
        // 使用线程池异步执行，绝不卡主线程
        Thread.ofVirtual().start(() -> {
            Path tempPath = null;
            try {
                // 确保目标目录存在
                Files.createDirectories(targetPath.getParent());

                // 先下载到临时文件（防止下载一半断电导致原文件损坏）
                tempPath = targetPath.resolveSibling(targetPath.getFileName().toString() + ".downloading");

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .GET()
                        .build();

                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200) {
                    callback.onError("HTTP 错误: " + response.statusCode());
                    return;
                }

                long totalBytes = response.headers().firstValueAsLong("content-length").orElse(-1L);
                long downloadedBytes = 0L;

                // 流式读取写入文件（防止几个G的模型撑爆内存）
                try (InputStream is = response.body();
                     var os = Files.newOutputStream(tempPath)) {

                    byte[] buffer = new byte[8192]; // 8KB 缓冲区
                    int bytesRead;

                    while ((bytesRead = is.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                        downloadedBytes += bytesRead;

                        // 通知 GUI 更新进度
                        final long current = downloadedBytes;
                        final long total = totalBytes;
                        // 切回主线程更新 UI (这里先直接调，GUI层会用包装处理线程)
                        callback.onProgress(current, total);
                    }
                }

                // 下载完成，将临时文件原子替换为正式文件
                Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                callback.onSuccess(targetPath);

            } catch (IOException | InterruptedException e) {
                Tianshu.LOGGER.error("模型下载失败: {}", url, e);
                callback.onError("下载失败: " + e.getMessage());
                // 发生异常，清理残留的临时文件
                if (tempPath != null) {
                    try { Files.deleteIfExists(tempPath); } catch (IOException ignored) {}
                }
            }
        });
    }
}
