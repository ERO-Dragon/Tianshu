package com.rheinmetal.tianshu.model;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class AsrModelDownloader {

    private static final String HF_OFFICIAL = "https://huggingface.co";
    private static final String HF_MIRROR = "https://hf-mirror.com";
    private static final String REVISION = "main";
    private static final Gson GSON = new Gson();

    private final IGameEnvironment env;
    private final Set<HttpURLConnection> activeConnections = ConcurrentHashMap.newKeySet();

    public interface DownloadProgressCallback {
        void onProgress(String label, int percent);
        void onComplete();
        void onError(String message);
    }

    @FunctionalInterface
    public interface DownloadControl {
        void awaitReady() throws IOException;
    }

    public static final class DownloadCancelledException extends IOException {
        public DownloadCancelledException() {
            super("Download was cancelled");
        }
    }

    public AsrModelDownloader(IGameEnvironment env) {
        this.env = env;
    }

    public void download(AsrModelInfo info, Path targetDir, String githubProxyUrl, DownloadProgressCallback callback) {
        try {
            downloadSync(info, targetDir, githubProxyUrl, callback, () -> {});
        } catch (Exception e) {
            callback.onError(e.getMessage() != null ? e.getMessage() : "ASR model download failed");
        }
    }

    public void downloadSync(AsrModelInfo info, Path targetDir, String githubProxyUrl, DownloadProgressCallback callback) throws Exception {
        downloadSync(info, targetDir, githubProxyUrl, callback, () -> {});
    }

    public void downloadSync(AsrModelInfo info, Path targetDir, String githubProxyUrl, DownloadProgressCallback callback, DownloadControl control) throws Exception {
        doDownload(info, targetDir, githubProxyUrl, callback, control == null ? () -> {} : control);
    }

    public void cancelActiveTransfers() {
        for (HttpURLConnection connection : activeConnections) {
            connection.disconnect();
        }
    }

    private void doDownload(AsrModelInfo info, Path targetDir, String githubProxyUrl, DownloadProgressCallback callback, DownloadControl control) throws Exception {
        Objects.requireNonNull(info, "info");
        Objects.requireNonNull(targetDir, "targetDir");
        List<String> requiredFiles = validateRequiredFiles(info);
        Path stagingDir = targetDir.resolveSibling(targetDir.getFileName() + "-staging");
        deleteRecursivelyIfExists(stagingDir);
        Files.createDirectories(stagingDir);
        try {
            if (info.isHfDownload()) {
                downloadFromHuggingFace(info, requiredFiles, stagingDir, callback, control);
            } else {
                downloadFromArchive(info, requiredFiles, stagingDir, targetDir, githubProxyUrl, callback, control);
            }
            control.awaitReady();
            validateRequiredFiles(requiredFiles, stagingDir);
            deleteRecursivelyIfExists(targetDir);
            Files.move(stagingDir, targetDir, StandardCopyOption.REPLACE_EXISTING);
            callback.onProgress("Complete", 100);
            callback.onComplete();
        } catch (Exception e) {
            deleteRecursivelyIfExists(stagingDir);
            throw e;
        }
    }

    private List<String> validateRequiredFiles(AsrModelInfo info) throws IOException {
        List<String> files = info.getAllRequiredFiles();
        if (files.isEmpty()) {
            throw new IOException("ASR model [" + info.getDisplayName() + "] has no required file entries");
        }
        for (String file : files) {
            Path relative = Path.of(file).normalize();
            if (relative.isAbsolute() || relative.startsWith("..")) {
                throw new IOException("ASR required file contains unsafe path: " + file);
            }
        }
        return files;
    }

    private void downloadFromHuggingFace(AsrModelInfo info, List<String> requiredFiles, Path stagingDir, DownloadProgressCallback callback, DownloadControl control) throws Exception {
        callback.onProgress("Checking network", 2);
        String baseUrl = resolveHfBaseUrl();
        String repoId = info.remoteRepoId();
        if (repoId.isBlank()) {
            throw new IOException("ASR model [" + info.getDisplayName() + "] has no HuggingFace repo id");
        }
        env.info("ASR HF download: repo=" + repoId + " source=" + baseUrl);

        callback.onProgress("Resolving file list", 5);
        List<String> repoFiles = fetchFileTree(baseUrl, repoId, REVISION);
        control.awaitReady();
        List<SourceTarget> downloads = resolveRequestedFiles(requiredFiles, repoFiles);
        env.info("ASR selected files: " + downloads.stream().map(SourceTarget::display).collect(Collectors.joining(", ")));

        int total = downloads.size();
        for (int i = 0; i < total; i++) {
            control.awaitReady();
            SourceTarget item = downloads.get(i);
            Path localPath = stagingDir.resolve(item.targetPath()).normalize();
            ensureWithinTarget(localPath, stagingDir);
            String resolveUrl = buildResolveUrl(baseUrl, repoId, REVISION, item.sourcePath());
            downloadSingleFile(resolveUrl, localPath, 3, control);
            control.awaitReady();
            int percent = 5 + (int) (((i + 1) / (double) total) * 90);
            callback.onProgress("Downloading model files", percent);
        }
    }

    private void downloadFromArchive(AsrModelInfo info, List<String> requiredFiles, Path stagingDir, Path targetDir, String githubProxyUrl, DownloadProgressCallback callback, DownloadControl control) throws Exception {
        callback.onProgress("Checking network", 2);
        boolean useProxy = shouldUseGithubProxy(githubProxyUrl);
        String finalUrl = buildGithubDownloadUrl(info.downloadUrl, githubProxyUrl, useProxy);
        env.info("ASR archive download: url=" + finalUrl + " (useProxy=" + useProxy + ")");

        Path archivePath = targetDir.resolveSibling(targetDir.getFileName() + archiveSuffix(info.downloadUrl));
        Path extractDir = targetDir.resolveSibling(targetDir.getFileName() + "-extract");
        Files.deleteIfExists(archivePath);
        deleteRecursivelyIfExists(extractDir);
        try {
            callback.onProgress("Downloading archive", 5);
            downloadSingleFileWithProgress(finalUrl, archivePath, 5, 60_000, control, (downloaded, total) -> {
                int percent = total > 0 ? Math.min(80, (int) (downloaded * 75 / total) + 5) : 40;
                callback.onProgress("Downloading archive", percent);
            });
            control.awaitReady();

            callback.onProgress("Extracting model", 82);
            Files.createDirectories(extractDir);
            extractArchive(archivePath, extractDir, info.downloadUrl);
            control.awaitReady();

            callback.onProgress("Materializing model files", 92);
            List<String> archiveFiles = listRelativeFiles(extractDir);
            for (SourceTarget item : resolveRequestedFiles(requiredFiles, archiveFiles)) {
                control.awaitReady();
                Path source = extractDir.resolve(item.sourcePath()).normalize();
                ensureWithinTarget(source, extractDir);
                Path target = stagingDir.resolve(item.targetPath()).normalize();
                ensureWithinTarget(target, stagingDir);
                Files.createDirectories(target.getParent());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            deleteRecursivelyIfExists(extractDir);
            Files.deleteIfExists(archivePath);
        }
    }

    private List<SourceTarget> resolveRequestedFiles(List<String> requestedFiles, List<String> availableFiles) throws IOException {
        List<SourceTarget> result = new ArrayList<>();
        for (String requested : requestedFiles) {
            String source = resolveRequestedFile(requested, availableFiles);
            result.add(new SourceTarget(source, requested));
        }
        return result;
    }

    private String resolveRequestedFile(String requested, List<String> availableFiles) throws IOException {
        String normalizedRequest = normalizePath(requested);
        List<String> exactMatches = availableFiles.stream()
                .filter(path -> normalizePath(path).equalsIgnoreCase(normalizedRequest))
                .toList();
        if (exactMatches.size() == 1) {
            return exactMatches.get(0);
        }
        if (exactMatches.size() > 1) {
            throw new IOException("ASR model file is ambiguous: " + requested + " -> " + String.join(", ", exactMatches));
        }

        String requestName = fileName(normalizedRequest);
        List<String> nameMatches = availableFiles.stream()
                .filter(path -> fileName(path).equalsIgnoreCase(requestName))
                .toList();
        if (nameMatches.size() == 1) {
            return nameMatches.get(0);
        }
        if (nameMatches.isEmpty()) {
            throw new IOException("ASR model file was not found in source: " + requested);
        }
        throw new IOException("ASR model file name is ambiguous: " + requested + " -> " + String.join(", ", nameMatches));
    }

    private void validateRequiredFiles(List<String> requiredFiles, Path modelDir) throws IOException {
        List<String> missing = new ArrayList<>();
        for (String file : requiredFiles) {
            Path path = modelDir.resolve(file).normalize();
            ensureWithinTarget(path, modelDir);
            if (!Files.isRegularFile(path)) {
                missing.add(file);
            }
        }
        if (!missing.isEmpty()) {
            throw new IOException("ASR model materialization failed, missing files: " + String.join(", ", missing));
        }
    }

    private String resolveHfBaseUrl() {
        try {
            String activeUrl = HuggingFaceDownloader.getActiveBaseUrl();
            if (HF_OFFICIAL.equals(activeUrl)) {
                env.info("HuggingFace official endpoint is reachable");
                return HF_OFFICIAL;
            }
        } catch (Exception ignored) {
        }
        env.info("Using HuggingFace mirror");
        return HF_MIRROR;
    }

    private boolean shouldUseGithubProxy(String githubProxyUrl) {
        if (githubProxyUrl == null || githubProxyUrl.isBlank()) return false;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("github.com", 443), 300);
            env.info("GitHub official endpoint is reachable");
            return false;
        } catch (IOException e) {
            env.info("GitHub official endpoint is unreachable, using proxy");
            return true;
        }
    }

    private String buildGithubDownloadUrl(String downloadUrl, String proxyUrl, boolean useProxy) {
        if (!useProxy || proxyUrl == null || proxyUrl.isBlank()) return downloadUrl;
        String normalizedProxy = proxyUrl.endsWith("/") ? proxyUrl : proxyUrl + "/";
        if (downloadUrl.startsWith(normalizedProxy)) return downloadUrl;
        return normalizedProxy + downloadUrl;
    }

    private String archiveSuffix(String downloadUrl) {
        if (downloadUrl == null) return ".archive";
        String lower = downloadUrl.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".tar.bz2")) return ".tar.bz2";
        if (lower.endsWith(".tar.gz")) return ".tar.gz";
        if (lower.endsWith(".tgz")) return ".tgz";
        if (lower.endsWith(".zip")) return ".zip";
        return ".archive";
    }

    private void extractArchive(Path archive, Path targetDir, String downloadUrl) throws IOException {
        String lower = downloadUrl == null ? "" : downloadUrl.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".tar.bz2")) {
            extractTarBz2(archive, targetDir);
            return;
        }
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) {
            extractTarGz(archive, targetDir);
            return;
        }
        if (lower.endsWith(".zip")) {
            extractZip(archive, targetDir);
            return;
        }
        throw new IOException("Unsupported ASR model archive type: " + downloadUrl);
    }

    private List<String> fetchFileTree(String baseUrl, String repoId, String revision) throws Exception {
        String url = String.format(
                "%s/api/models/%s/tree/%s?recursive=true&expand=false",
                baseUrl,
                encodeRepoId(repoId),
                URLEncoder.encode(revision, StandardCharsets.UTF_8)
        );

        HttpURLConnection conn = openConnection(url);
        conn.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        conn.setReadTimeout((int) Duration.ofSeconds(15).toMillis());
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "Tianshu-ASR-Downloader/1.0");

        int code = conn.getResponseCode();
        if (code != 200) throw new IOException("HF tree API failed. repo=" + repoId + " code=" + code);

        try (InputStream is = conn.getInputStream(); Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            JsonArray root = GSON.fromJson(reader, JsonArray.class);
            List<String> result = new ArrayList<>();
            for (JsonElement element : root) {
                JsonObject node = element.getAsJsonObject();
                if (!"file".equalsIgnoreCase(node.get("type").getAsString())) continue;
                result.add(node.get("path").getAsString());
            }
            return result;
        } finally {
            closeConnection(conn);
        }
    }

    private List<String> listRelativeFiles(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .collect(Collectors.toList());
        }
    }

    private String buildResolveUrl(String baseUrl, String repoId, String revision, String filePath) {
        String encodedPath = Arrays.stream(filePath.split("/"))
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

    private HttpURLConnection openConnection(String url) throws IOException {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URI(url).toURL().openConnection();
            activeConnections.add(connection);
            return connection;
        } catch (URISyntaxException e) {
            throw new IOException("Invalid download URL: " + url, e);
        }
    }

    private void closeConnection(HttpURLConnection connection) {
        if (connection == null) {
            return;
        }
        activeConnections.remove(connection);
        connection.disconnect();
    }

    private void downloadSingleFile(String url, Path target, int maxRetries, DownloadControl control) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);

        IOException lastException = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpURLConnection conn = openConnection(url);
                conn.setConnectTimeout((int) Duration.ofSeconds(15).toMillis());
                conn.setReadTimeout((int) Duration.ofSeconds(120).toMillis());
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "Tianshu-ASR-Downloader/1.0");

                if (conn.getResponseCode() != 200) throw new IOException("HTTP " + conn.getResponseCode());

                try (InputStream in = conn.getInputStream(); OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) != -1) {
                        control.awaitReady();
                        out.write(buf, 0, len);
                    }
                } finally {
                    closeConnection(conn);
                }

                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                env.info("Downloaded: " + target.getFileName());
                return;
            } catch (IOException e) {
                if (isDownloadCancelled(e)) {
                    throw e;
                }
                lastException = e;
                control.awaitReady();
                env.warn("Download retry " + attempt + "/" + maxRetries + ": " + target.getFileName() + " - " + e.getMessage());
                try {
                    sleepWithControl(1000L * attempt, control);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Download thread was interrupted", ignored);
                }
            }
        }
        throw new IOException("Download failed after " + maxRetries + " retries: " + url, lastException);
    }

    private void downloadSingleFileWithProgress(String urlString, Path targetPath, int maxRetries, int timeoutMillis, DownloadControl control, ProgressListener listener) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            control.awaitReady();
            HttpURLConnection conn = null;
            Path tempPath = targetPath.resolveSibling(targetPath.getFileName() + ".downloading");
            try {
                Files.createDirectories(targetPath.getParent());
                conn = openConnection(urlString);
                conn.setConnectTimeout(timeoutMillis);
                conn.setReadTimeout(timeoutMillis);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "Tianshu-ASR-Downloader/1.0");
                int code = conn.getResponseCode();
                if (code != 200) throw new IOException("HTTP " + code);
                long total = conn.getContentLengthLong();
                long downloaded = 0L;
                try (InputStream in = conn.getInputStream(); OutputStream out = Files.newOutputStream(tempPath)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        control.awaitReady();
                        out.write(buffer, 0, read);
                        downloaded += read;
                        listener.onProgress(downloaded, total);
                    }
                }
                Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException e) {
                if (isDownloadCancelled(e)) {
                    throw e;
                }
                last = e;
                Files.deleteIfExists(tempPath);
                control.awaitReady();
                try {
                    sleepWithControl(1000L * attempt, control);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Download thread was interrupted", interrupted);
                }
            } finally {
                closeConnection(conn);
            }
        }
        throw last != null ? last : new IOException("Download failed");
    }

    private void extractTarBz2(Path archive, Path targetDir) throws IOException {
        try (InputStream fis = Files.newInputStream(archive);
             InputStream bis = new BufferedInputStream(fis);
             BZip2CompressorInputStream bzIn = new BZip2CompressorInputStream(bis);
             TarArchiveInputStream tarIn = new TarArchiveInputStream(bzIn)) {
            TarArchiveEntry entry;
            while ((entry = tarIn.getNextEntry()) != null) {
                Path outputPath = targetDir.resolve(entry.getName()).normalize();
                ensureWithinTarget(outputPath, targetDir);
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

    private void extractTarGz(Path archive, Path targetDir) throws IOException {
        try (InputStream fis = Files.newInputStream(archive);
             InputStream bis = new BufferedInputStream(fis);
             GzipCompressorInputStream gzIn = new GzipCompressorInputStream(bis);
             TarArchiveInputStream tarIn = new TarArchiveInputStream(gzIn)) {
            extractTarStream(tarIn, targetDir);
        }
    }

    private void extractTarStream(TarArchiveInputStream tarIn, Path targetDir) throws IOException {
        TarArchiveEntry entry;
        while ((entry = tarIn.getNextEntry()) != null) {
            Path outputPath = targetDir.resolve(entry.getName()).normalize();
            ensureWithinTarget(outputPath, targetDir);
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

    private void extractZip(Path archive, Path targetDir) throws IOException {
        try (InputStream fis = Files.newInputStream(archive);
             InputStream bis = new BufferedInputStream(fis);
             ZipArchiveInputStream zipIn = new ZipArchiveInputStream(bis)) {
            ZipArchiveEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                Path outputPath = targetDir.resolve(entry.getName()).normalize();
                ensureWithinTarget(outputPath, targetDir);
                if (entry.isDirectory()) {
                    Files.createDirectories(outputPath);
                    continue;
                }
                Files.createDirectories(outputPath.getParent());
                try (OutputStream out = Files.newOutputStream(outputPath)) {
                    zipIn.transferTo(out);
                }
            }
        }
    }

    private String normalizePath(String path) {
        return path == null ? "" : path.trim().replace('\\', '/');
    }

    private String fileName(String path) {
        String normalized = normalizePath(path);
        int index = normalized.lastIndexOf('/');
        return index >= 0 ? normalized.substring(index + 1) : normalized;
    }

    private void ensureWithinTarget(Path path, Path targetDir) throws IOException {
        if (!path.toAbsolutePath().normalize().startsWith(targetDir.toAbsolutePath().normalize())) {
            throw new IOException("Unsafe path detected: " + path);
        }
    }

    private void deleteRecursivelyIfExists(Path path) throws IOException {
        if (Files.exists(path)) deleteRecursively(path);
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException ioException) throw ioException;
            throw e;
        }
    }

    private void sleepWithControl(long millis, DownloadControl control) throws IOException, InterruptedException {
        if (millis <= 0L) {
            return;
        }
        long deadline = System.currentTimeMillis() + millis;
        while (true) {
            control.awaitReady();
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0L) {
                return;
            }
            Thread.sleep(Math.min(remaining, 200L));
        }
    }

    private boolean isDownloadCancelled(IOException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof DownloadCancelledException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private record SourceTarget(String sourcePath, String targetPath) {
        private String display() {
            return sourcePath + " -> " + targetPath;
        }
    }

    private interface ProgressListener {
        void onProgress(long downloaded, long total);
    }
}
