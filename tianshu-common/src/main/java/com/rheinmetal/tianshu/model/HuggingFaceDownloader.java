package com.rheinmetal.tianshu.model;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.IGameEnvironment;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

public class HuggingFaceDownloader {

    private static final String HF_OFFICIAL = "https://huggingface.co";
    private static final String HF_MIRROR = "https://hf-mirror.com";
    private static final Gson GSON = new Gson();

    private static volatile String activeBaseUrl = null;

    private final IGameEnvironment env;

    @FunctionalInterface
    public interface DownloadControl {
        void checkCancelled() throws IOException;
    }

    public interface DownloadProgressListener {
        default void onFileListResolved(int totalFiles) {
        }

        default void onFileProgress(String filePath, int fileIndex, int totalFiles, long downloadedBytes, long totalBytes) {
        }
    }

    public HuggingFaceDownloader(IGameEnvironment env) {
        this.env = env;
    }

    public static synchronized String getActiveBaseUrl() {
        if (activeBaseUrl != null) {
            return activeBaseUrl;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("huggingface.co", 443), 300);
            activeBaseUrl = HF_OFFICIAL;
        } catch (IOException e) {
            activeBaseUrl = HF_MIRROR;
        }
        return activeBaseUrl;
    }

    public static synchronized void resetDomainCache() {
        activeBaseUrl = null;
    }

    public void downloadModelFiles(
            String repoId,
            Path targetDir,
            String revision,
            boolean skipExisting,
            int maxRetries
    ) throws Exception {
        downloadModelFiles(repoId, targetDir, revision, skipExisting, maxRetries, null, null);
    }

    public void downloadModelFiles(
            String repoId,
            Path targetDir,
            String revision,
            boolean skipExisting,
            int maxRetries,
            DownloadControl control,
            DownloadProgressListener progress
    ) throws Exception {
        Objects.requireNonNull(repoId, "repoId");
        Objects.requireNonNull(targetDir, "targetDir");

        env.info("正在解析模型文件列表...");

        Files.createDirectories(targetDir);

        String baseUrl = getActiveBaseUrl();
        env.info("HuggingFace 下载: repo=" + repoId + " source=" + baseUrl);

        checkControl(control);
        List<String> allFiles = fetchFileTree(baseUrl, repoId, revision);

        List<String> toDownload = allFiles.stream()
                .filter(path -> !shouldSkipFile(path))
                .collect(Collectors.toList());

        env.info("需要下载 " + toDownload.size() + " 个文件（已过滤 " + (allFiles.size() - toDownload.size()) + " 个）");
        if (progress != null) {
            progress.onFileListResolved(toDownload.size());
        }

        int totalFiles = toDownload.size();
        for (int i = 0; i < totalFiles; i++) {
            checkControl(control);
            String filePath = toDownload.get(i);
            int fileIndex = i + 1;
            Path localPath = targetDir.resolve(filePath).normalize();
            ensureWithinTarget(localPath, targetDir);

            if (skipExisting && Files.exists(localPath) && Files.size(localPath) > 0) {
                if (progress != null) {
                    progress.onFileProgress(filePath, fileIndex, totalFiles, 1, 1);
                }
                continue;
            }

            String resolveUrl = buildResolveUrl(baseUrl, repoId, revision, filePath);
            downloadFile(resolveUrl, localPath, maxRetries, control, (downloaded, total) -> {
                if (progress != null) {
                    progress.onFileProgress(filePath, fileIndex, totalFiles, downloaded, total);
                }
            });
        }
    }

    public void downloadVocoder(Path vocoderDir, int maxRetries) throws Exception {
        downloadVocoder(vocoderDir, maxRetries, null, null);
    }

    public void downloadVocoder(Path vocoderDir, int maxRetries, DownloadControl control, DownloadProgressListener progress) throws Exception {
        Files.createDirectories(vocoderDir);
        String baseUrl = getActiveBaseUrl();
        String repoId = "csukuangfj/sherpa-onnx-vocoders";
        checkControl(control);
        List<String> allFiles = fetchFileTree(baseUrl, repoId, "main");
        List<String> toDownload = allFiles.stream()
                .filter(filePath -> filePath.toLowerCase().endsWith(".onnx") && !shouldSkipFile(filePath))
                .collect(Collectors.toList());
        if (progress != null) {
            progress.onFileListResolved(toDownload.size());
        }
        int totalFiles = toDownload.size();
        for (int i = 0; i < totalFiles; i++) {
            checkControl(control);
            String filePath = toDownload.get(i);
            int fileIndex = i + 1;
            Path localPath = vocoderDir.resolve(filePath).normalize();
            ensureWithinTarget(localPath, vocoderDir);
            if (!Files.exists(localPath) || Files.size(localPath) == 0) {
                String resolveUrl = buildResolveUrl(baseUrl, repoId, "main", filePath);
                downloadFile(resolveUrl, localPath, maxRetries, control, (downloaded, total) -> {
                    if (progress != null) {
                        progress.onFileProgress(filePath, fileIndex, totalFiles, downloaded, total);
                    }
                });
            } else if (progress != null) {
                progress.onFileProgress(filePath, fileIndex, totalFiles, 1, 1);
            }
        }
    }

    public void downloadSingleFile(String repoId, String filePath, Path targetFile, String revision, int maxRetries) throws Exception {
        downloadSingleFile(repoId, filePath, targetFile, revision, maxRetries, null, null);
    }

    public void downloadSingleFile(String repoId, String filePath, Path targetFile, String revision, int maxRetries, DownloadControl control, DownloadProgressListener progress) throws Exception {
        Objects.requireNonNull(repoId, "repoId");
        Objects.requireNonNull(filePath, "filePath");
        Objects.requireNonNull(targetFile, "targetFile");

        Files.createDirectories(targetFile.getParent());
        ensureWithinTarget(targetFile, targetFile.getParent());

        if (Files.exists(targetFile) && Files.size(targetFile) > 0) {
            env.info("文件已存在，跳过: " + targetFile.getFileName());
            if (progress != null) {
                progress.onFileListResolved(1);
                progress.onFileProgress(filePath, 1, 1, 1, 1);
            }
            return;
        }

        String primary = getActiveBaseUrl();
        String fallback = HF_OFFICIAL.equals(primary) ? HF_MIRROR : HF_OFFICIAL;

        String primaryUrl = buildResolveUrl(primary, repoId, revision, filePath);
        String fallbackUrl = buildResolveUrl(fallback, repoId, revision, filePath);

        env.info("单文件下载: repo=" + repoId + " file=" + filePath + " primary=" + primary);
        if (progress != null) {
            progress.onFileListResolved(1);
        }

        try {
            downloadFile(primaryUrl, targetFile, maxRetries, control, (downloaded, total) -> {
                if (progress != null) {
                    progress.onFileProgress(filePath, 1, 1, downloaded, total);
                }
            });
        } catch (IOException e) {
            checkControl(control);
            env.warn("主源下载失败，切换到回退源: " + fallback + " - " + e.getMessage());
            downloadFile(fallbackUrl, targetFile, maxRetries, control, (downloaded, total) -> {
                if (progress != null) {
                    progress.onFileProgress(filePath, 1, 1, downloaded, total);
                }
            });
        }
    }

    private boolean shouldSkipFile(String path) {
        String lowerPath = path.toLowerCase();

        if (lowerPath.startsWith("dict/") || lowerPath.startsWith("dict\\")) {
            return true;
        }
        if (lowerPath.endsWith(".py")) {
            return true;
        }

        String fileName = lowerPath;
        int lastSlash = lowerPath.lastIndexOf('/');
        if (lastSlash != -1) {
            fileName = lowerPath.substring(lastSlash + 1);
        }

        if (fileName.equals(".gitattributes")) {
            return true;
        }
        if (fileName.startsWith("readme.") || fileName.equals("readme")) {
            return true;
        }

        return false;
    }

    private List<String> fetchFileTree(String baseUrl, String repoId, String revision) throws Exception {
        String encodedRepoId = encodeRepoId(repoId);
        String url = String.format(
                "%s/api/models/%s/tree/%s?recursive=true&expand=false",
                baseUrl,
                encodedRepoId,
                URLEncoder.encode(revision, StandardCharsets.UTF_8)
        );

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        conn.setReadTimeout((int) Duration.ofSeconds(15).toMillis());
        conn.setRequestMethod("GET");

        int code = conn.getResponseCode();
        if (code != 200) {
            throw new IOException("HF tree API failed. repo=" + repoId + " code=" + code);
        }

        try (InputStream is = conn.getInputStream();
             Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            JsonArray root = GSON.fromJson(reader, JsonArray.class);
            List<String> result = new ArrayList<>();
            for (JsonElement element : root) {
                JsonObject node = element.getAsJsonObject();
                if (!"file".equalsIgnoreCase(node.get("type").getAsString())) {
                    continue;
                }
                result.add(node.get("path").getAsString());
            }
            return result;
        } finally {
            conn.disconnect();
        }
    }

    private String buildResolveUrl(String baseUrl, String repoId, String revision, String filePath) {
        String[] segments = filePath.split("/");
        String encodedPath = Arrays.stream(segments)
                .map(s -> URLEncoder.encode(s, StandardCharsets.UTF_8))
                .collect(Collectors.joining("/"));

        return String.format(
                "%s/%s/resolve/%s/%s",
                baseUrl,
                encodeRepoId(repoId),
                URLEncoder.encode(revision, StandardCharsets.UTF_8),
                encodedPath
        );
    }

    private String encodeRepoId(String repoId) {
        String[] parts = repoId.split("/", 2);
        if (parts.length == 2) {
            return URLEncoder.encode(parts[0], StandardCharsets.UTF_8) + "/" + URLEncoder.encode(parts[1], StandardCharsets.UTF_8);
        }
        return URLEncoder.encode(repoId, StandardCharsets.UTF_8);
    }

    private interface FileProgressListener {
        void onProgress(long downloaded, long total);
    }

    private void downloadFile(String url, Path target, int maxRetries) throws IOException {
        downloadFile(url, target, maxRetries, null, null);
    }

    private void downloadFile(String url, Path target, int maxRetries, DownloadControl control, FileProgressListener progress) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        IOException lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            checkControl(control);
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout((int) Duration.ofSeconds(15).toMillis());
                conn.setReadTimeout((int) Duration.ofSeconds(120).toMillis());
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "Tianshu-HF-Downloader/1.0");

                if (conn.getResponseCode() != 200) {
                    throw new IOException("HTTP " + conn.getResponseCode());
                }

                long total = conn.getContentLengthLong();
                long downloaded = 0L;
                try (InputStream in = conn.getInputStream();
                     OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) != -1) {
                        checkControl(control);
                        out.write(buf, 0, len);
                        downloaded += len;
                        if (progress != null) {
                            progress.onProgress(downloaded, total);
                        }
                    }
                }

                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                env.info("下载完成: " + target.getFileName());
                return;
            } catch (IOException e) {
                lastException = e;
                Files.deleteIfExists(tmp);
                checkControl(control);
                env.warn("下载重试 " + attempt + "/" + maxRetries + ": " + target.getFileName() + " - " + e.getMessage());
                try {
                    Thread.sleep(1000L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("下载线程被中断", interrupted);
                }
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        throw new IOException("下载失败，重试 " + maxRetries + " 次后仍不成功: " + url, lastException);
    }

    private void checkControl(DownloadControl control) throws IOException {
        if (control != null) {
            control.checkCancelled();
        }
    }

    private void ensureWithinTarget(Path path, Path targetDir) throws IOException {
        if (!path.toAbsolutePath().normalize().startsWith(targetDir.toAbsolutePath().normalize())) {
            throw new IOException("路径穿越检测: " + path);
        }
    }
}
