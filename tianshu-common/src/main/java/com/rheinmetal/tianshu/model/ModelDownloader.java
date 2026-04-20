package com.rheinmetal.tianshu.model;

import com.rheinmetal.tianshu.api.IGameEnvironment;

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

    public interface DownloadCallback {
        void onProgress(long downloadedBytes, long totalBytes);
        void onSuccess(Path savedPath);
        void onError(String errorMessage);
    }

    private static final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static void downloadAsync(String url, Path targetPath, DownloadCallback callback) {
        Thread.ofVirtual().start(() -> {
            Path tempPath = null;
            try {
                Files.createDirectories(targetPath.getParent());
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

                try (InputStream is = response.body();
                     var os = Files.newOutputStream(tempPath)) {

                    byte[] buffer = new byte[8192];
                    int bytesRead;

                    while ((bytesRead = is.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                        downloadedBytes += bytesRead;

                        final long current = downloadedBytes;
                        final long total = totalBytes;
                        callback.onProgress(current, total);
                    }
                }

                Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                callback.onSuccess(targetPath);

            } catch (IOException | InterruptedException e) {
                callback.onError("下载失败: " + e.getMessage());
                if (tempPath != null) {
                    try { Files.deleteIfExists(tempPath); } catch (IOException ignored) {}
                }
            }
        });
    }
}
