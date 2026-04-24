package com.rheinmetal.tianshu.model;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

public class AsrModelDownloader {

    private static final String HF_OFFICIAL = "https://huggingface.co";
    private static final String HF_MIRROR = "https://hf-mirror.com";
    private static final Gson GSON = new Gson();

    private final IGameEnvironment env;
    private volatile boolean downloadPaused = false;
    private volatile boolean downloadCancelled = false;

    public interface DownloadProgressCallback {
        void onProgress(String label, int percent);
        void onComplete();
        void onError(String message);
    }

    public AsrModelDownloader(IGameEnvironment env) {
        this.env = env;
    }

    public boolean isDownloadPaused() {
        return downloadPaused;
    }

    public void pauseDownload() {
        downloadPaused = true;
    }

    public void resumeDownload() {
        downloadPaused = false;
    }

    public void cancelDownload() {
        downloadCancelled = true;
        downloadPaused = false;
    }

    public void download(AsrModelInfo info, Path targetDir, String githubProxyUrl, DownloadProgressCallback callback) {
        downloadCancelled = false;
        downloadPaused = false;
        Thread.ofVirtual().start(() -> {
            try {
                doDownload(info, targetDir, githubProxyUrl, callback);
            } catch (Exception e) {
                callback.onError(e.getMessage() != null ? e.getMessage() : "下载失败");
            }
        });
    }

    public void downloadSync(AsrModelInfo info, Path targetDir, String githubProxyUrl, DownloadProgressCallback callback) throws Exception {
        downloadCancelled = false;
        downloadPaused = false;
        doDownload(info, targetDir, githubProxyUrl, callback);
    }

    private void doDownload(AsrModelInfo info, Path targetDir, String githubProxyUrl, DownloadProgressCallback callback) throws Exception {
        Files.createDirectories(targetDir);
        if (info.isHfDownload()) {
            downloadFromHuggingFace(info, targetDir, callback);
        } else {
            downloadFromGitHub(info, targetDir, githubProxyUrl, callback);
        }
        callback.onProgress("完成", 100);
        callback.onComplete();
    }

    private void downloadFromHuggingFace(AsrModelInfo info, Path targetDir, DownloadProgressCallback callback) throws Exception {
        callback.onProgress("探测网络环境", 2);

        String baseUrl = resolveHfBaseUrl();
        env.info("ASR HF 下载: repo=" + info.id + " source=" + baseUrl);

        callback.onProgress("解析文件列表", 5);
        List<String> allFiles = fetchFileTree(baseUrl, info.id, "main");

        Set<String> requiredSet = new HashSet<>(info.getAllRequiredFiles());

        List<String> toDownload = allFiles.stream()
                .filter(path -> {
                    String fileName = extractFileName(path);
                    return requiredSet.contains(fileName) || shouldIncludeExtraFile(path, info);
                })
                .collect(Collectors.toList());

        env.info("ASR 需要下载 " + toDownload.size() + " 个文件（总文件 " + allFiles.size() + " 个）");

        int total = toDownload.size();
        int completed = 0;
        for (String filePath : toDownload) {
            waitIfDownloadPaused();

            String flatFileName = extractFileName(filePath);
            Path localPath = targetDir.resolve(flatFileName).normalize();
            ensureWithinTarget(localPath, targetDir);

            if (Files.exists(localPath)) {
                completed++;
                continue;
            }

            String resolveUrl = buildResolveUrl(baseUrl, info.id, "main", filePath);
            downloadSingleFile(resolveUrl, localPath, 3);
            completed++;

            int percent = 5 + (int) ((completed / (double) total) * 90);
            callback.onProgress("下载中", percent);
        }
    }

    private void downloadFromGitHub(AsrModelInfo info, Path targetDir, String githubProxyUrl, DownloadProgressCallback callback) throws Exception {
        callback.onProgress("探测网络环境", 2);

        boolean useProxy = shouldUseGithubProxy(githubProxyUrl);
        String finalUrl = buildGithubDownloadUrl(info.downloadUrl, githubProxyUrl, useProxy);
        env.info("ASR GitHub 下载: url=" + finalUrl + " (useProxy=" + useProxy + ")");

        Path archivePath = targetDir.resolveSibling(targetDir.getFileName() + ".tar.bz2");
        try { Files.deleteIfExists(archivePath); } catch (IOException ignored) {}

        callback.onProgress("下载压缩包", 5);
        downloadSingleFileWithProgress(finalUrl, archivePath, 5, 60_000, (downloaded, total) -> {
            int percent = total > 0 ? Math.min(85, (int) (downloaded * 80 / total) + 5) : 40;
            callback.onProgress("下载压缩包", percent);
        });

        callback.onProgress("解压模型", 90);
        Path tempDir = targetDir.resolveSibling(targetDir.getFileName() + "-extract");
        deleteRecursivelyIfExists(tempDir);
        Files.createDirectories(tempDir);
        extractTarBz2(archivePath, tempDir);

        Path extractedModelDir = resolveExtractedModelDir(tempDir);
        deleteRecursivelyIfExists(targetDir);
        Files.move(extractedModelDir, targetDir, StandardCopyOption.REPLACE_EXISTING);
        deleteRecursivelyIfExists(tempDir);
        Files.deleteIfExists(archivePath);

        callback.onProgress("解压完成", 95);
    }

    private String resolveHfBaseUrl() {
        try {
            String activeUrl = HuggingFaceDownloader.getActiveBaseUrl();
            if (HF_OFFICIAL.equals(activeUrl)) {
                env.info("HuggingFace 官方可达，使用官方源");
                return HF_OFFICIAL;
            }
        } catch (Exception ignored) {
        }
        env.info("HuggingFace 官方不可达，使用镜像源");
        return HF_MIRROR;
    }

    private boolean shouldUseGithubProxy(String githubProxyUrl) {
        if (githubProxyUrl == null || githubProxyUrl.isBlank()) return false;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("github.com", 443), 300);
            env.info("GitHub 官方可达，不使用代理");
            return false;
        } catch (IOException e) {
            env.info("GitHub 官方不可达，使用代理");
            return true;
        }
    }

    private String buildGithubDownloadUrl(String downloadUrl, String proxyUrl, boolean useProxy) {
        if (!useProxy || proxyUrl == null || proxyUrl.isBlank()) {
            return downloadUrl;
        }
        String normalizedProxy = proxyUrl.endsWith("/") ? proxyUrl : proxyUrl + "/";
        if (downloadUrl.startsWith(normalizedProxy)) {
            return downloadUrl;
        }
        return normalizedProxy + downloadUrl;
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
            throw new IOException("HF tree API 失败. repo=" + repoId + " code=" + code);
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

    private boolean shouldIncludeExtraFile(String path, AsrModelInfo info) {
        String lowerPath = path.toLowerCase();
        if (lowerPath.startsWith("dict/") || lowerPath.startsWith("dict\\")) return false;
        if (lowerPath.endsWith(".py")) return false;
        String fileName = extractFileName(path);
        if (fileName.equals(".gitattributes")) return false;
        if (fileName.startsWith("readme.") || fileName.equals("readme")) return false;
        if (info.supportHotwords && fileName.equals("hotwords.txt")) return true;
        return false;
    }

    private String extractFileName(String path) {
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash == -1) lastSlash = path.lastIndexOf('\\');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    private void downloadSingleFile(String url, Path target, int maxRetries) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}

        IOException lastException = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout((int) Duration.ofSeconds(15).toMillis());
                conn.setReadTimeout((int) Duration.ofSeconds(120).toMillis());
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "MC-Mod-ASR-Downloader/1.0");

                if (conn.getResponseCode() != 200) {
                    throw new IOException("HTTP " + conn.getResponseCode());
                }

                try (InputStream in = conn.getInputStream();
                     OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) != -1) {
                        if (downloadCancelled) throw new IOException("下载已取消");
                        waitIfDownloadPaused();
                        out.write(buf, 0, len);
                    }
                } finally {
                    conn.disconnect();
                }

                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                env.info("下载完成: " + target.getFileName());
                return;
            } catch (IOException e) {
                lastException = e;
                env.warn("下载重试 " + attempt + "/" + maxRetries + ": " + target.getFileName() + " - " + e.getMessage());
                try { Thread.sleep(1000L * attempt); } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        throw new IOException("下载失败，重试 " + maxRetries + " 次后仍不成功: " + url, lastException);
    }

    private void downloadSingleFileWithProgress(String urlString, Path targetPath, int maxRetries, int timeoutMillis, ProgressListener listener) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            waitIfDownloadPaused();
            HttpURLConnection conn = null;
            Path tempPath = targetPath.resolveSibling(targetPath.getFileName() + ".downloading");
            try {
                Files.createDirectories(targetPath.getParent());
                conn = (HttpURLConnection) new URL(urlString).openConnection();
                conn.setConnectTimeout(timeoutMillis);
                conn.setReadTimeout(timeoutMillis);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "Tianshu-ASR-Downloader/1.0");
                int code = conn.getResponseCode();
                if (code != 200) {
                    throw new IOException("HTTP 错误: " + code);
                }
                long total = conn.getContentLengthLong();
                long downloaded = 0L;
                try (InputStream in = conn.getInputStream();
                     OutputStream out = Files.newOutputStream(tempPath)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        if (downloadCancelled) throw new IOException("下载已取消");
                        waitIfDownloadPaused();
                        out.write(buffer, 0, read);
                        downloaded += read;
                        listener.onProgress(downloaded, total);
                    }
                }
                Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException e) {
                last = e;
                Files.deleteIfExists(tempPath);
                if (downloadCancelled) throw e;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        throw last != null ? last : new IOException("下载失败");
    }

    private void extractTarBz2(Path archive, Path targetDir) throws IOException {
        try (InputStream fis = Files.newInputStream(archive);
             InputStream bis = new BufferedInputStream(fis);
             BZip2CompressorInputStream bzIn = new BZip2CompressorInputStream(bis);
             TarArchiveInputStream tarIn = new TarArchiveInputStream(bzIn)) {
            TarArchiveEntry entry;
            while ((entry = tarIn.getNextEntry()) != null) {
                Path outputPath = targetDir.resolve(entry.getName()).normalize();
                if (!outputPath.startsWith(targetDir.normalize())) {
                    throw new IOException("非法压缩包路径: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(outputPath);
                    continue;
                }
                Files.createDirectories(outputPath.getParent());
                try (OutputStream out = Files.newOutputStream(outputPath)) {
                    tarIn.transferTo(out);
                }
            }
        }
    }

    private Path resolveExtractedModelDir(Path extractedRoot) throws IOException {
        try (var stream = Files.list(extractedRoot)) {
            List<Path> entries = stream.toList();
            if (entries.size() == 1 && Files.isDirectory(entries.get(0))) {
                return entries.get(0);
            }
        }
        return extractedRoot;
    }

    private void ensureWithinTarget(Path path, Path targetDir) throws IOException {
        if (!path.toAbsolutePath().normalize().startsWith(targetDir.toAbsolutePath().normalize())) {
            throw new IOException("路径穿越检测: " + path);
        }
    }

    private void waitIfDownloadPaused() throws IOException {
        while (downloadPaused) {
            if (downloadCancelled) throw new IOException("下载已取消");
            try { Thread.sleep(200); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("下载线程被中断", e);
            }
        }
        if (downloadCancelled) throw new IOException("下载已取消");
    }

    private void deleteRecursivelyIfExists(Path path) throws IOException {
        if (Files.exists(path)) deleteRecursively(path);
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException ioException) throw ioException;
            throw e;
        }
    }

    private interface ProgressListener {
        void onProgress(long downloaded, long total);
    }
}
