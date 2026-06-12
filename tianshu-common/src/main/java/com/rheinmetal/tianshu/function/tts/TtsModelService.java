package com.rheinmetal.tianshu.function.tts;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.function.tts.runtime.TtsModelSnapshot;
import com.rheinmetal.tianshu.model.HuggingFaceDownloader;
import com.rheinmetal.tianshu.model.ModelSettings;
import com.rheinmetal.tianshu.model.TtsModelInfo;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutorManager;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TtsModelService {

    public interface DownloadProgressCallback {
        void onProgress(String label, int percent);
        void onComplete();
        void onError(String message);
    }

    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final ProtocolExecutorManager executorManager;
    private volatile boolean downloadCancelled = false;
    private volatile boolean downloadPaused = false;

    public TtsModelService(IGameEnvironment env, ITianshuConfig config, ProtocolExecutorManager executorManager) {
        this.env = env;
        this.config = config;
        this.executorManager = executorManager;
    }

    public List<TtsModelInfo> catalog() {
        List<TtsModelInfo> catalog = TtsModelInfo.loadCatalog();
        return catalog == null ? Collections.emptyList() : catalog;
    }

    public TtsModelInfo findModelByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (TtsModelInfo info : catalog()) {
            if (info != null && info.name != null && info.name.equalsIgnoreCase(name.trim())) {
                return info;
            }
        }
        return null;
    }

    public String currentConfiguredModelName() {
        String configured = config.getCustomTtsName();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        TtsModelInfo info = resolveCurrentModelInfo();
        return info == null || info.name == null ? "" : info.name;
    }

    public void useModel(String modelName) {
        if (modelName != null && !modelName.isBlank()) {
            config.setCustomTtsName(modelName.trim());
        }
    }

    public ModelSettings.TtsSettings loadSettings(TtsModelInfo info) {
        Path modelDir = info == null ? resolveCurrentModelDir() : resolveModelDir(info);
        return modelDir == null ? new ModelSettings.TtsSettings() : ModelSettings.loadTtsSettings(modelDir);
    }

    public void saveSettings(TtsModelInfo info, ModelSettings.TtsSettings settings) {
        Path modelDir = info == null ? resolveCurrentModelDir() : resolveModelDir(info);
        if (modelDir == null || settings == null) {
            return;
        }
        try {
            Files.createDirectories(modelDir);
            ModelSettings.saveTtsSettings(modelDir, settings);
        } catch (Exception e) {
            env.error("保存 TTS 模型设置失败", e);
        }
    }

    public TtsModelInfo resolveCurrentModelInfo() {
        Path modelPath = config.getTtsModelPath();
        if (modelPath == null || modelPath.getFileName() == null) {
            return null;
        }
        String dirName = modelPath.getFileName().toString();
        List<TtsModelInfo> matchedZipVoice = new ArrayList<>();
        for (TtsModelInfo info : catalog()) {
            if (info == null || info.name == null) {
                continue;
            }
            if (info.name.equalsIgnoreCase(dirName) || modelPath.endsWith(info.name)) {
                return info;
            }
            if ("zipvoice".equals(info.getEngineType())) {
                matchedZipVoice.add(info);
            }
        }
        if ("ZipVoice".equalsIgnoreCase(dirName)) {
            for (TtsModelInfo info : matchedZipVoice) {
                if ("ZipVoice-int8".equalsIgnoreCase(info.name)) {
                    return info;
                }
            }
            return matchedZipVoice.isEmpty() ? null : matchedZipVoice.get(0);
        }
        return null;
    }

    public Path resolveCurrentModelDir() {
        TtsModelInfo info = resolveCurrentModelInfo();
        if (info != null) {
            return resolveModelDir(info);
        }
        return config.getTtsModelPath();
    }

    public Path resolveModelDir(TtsModelInfo info) {
        if (info == null || info.name == null) {
            return null;
        }
        String modelDirName = "zipvoice".equals(info.getEngineType()) ? "ZipVoice" : info.name;
        return config.getTtsBasePath().resolve("model").resolve(modelDirName);
    }

    public TtsModelSnapshot snapshot() {
        Path modelDir = resolveCurrentModelDir();
        if (modelDir == null) {
            return TtsModelSnapshot.unconfigured();
        }
        TtsModelInfo info = resolveCurrentModelInfo();
        boolean directoryExists = Files.isDirectory(modelDir);
        boolean hasContent = info == null ? hasModelContent(modelDir) : hasModelContent(info);
        return new TtsModelSnapshot(
                true,
                info != null,
                directoryExists,
                hasContent,
                info == null ? "" : info.name,
                info == null ? modelDir.getFileName().toString() : info.getDisplayName(),
                info == null ? "" : info.id,
                info == null ? "legacy" : info.getEngineType(),
                info == null ? "" : scoreTier(info.getRecommendationScore()),
                info == null ? "" : scoreTier(info.getPerformanceScore()),
                info != null && info.supportsVoiceClone(),
                info != null && info.supportsSpeakerSelection(),
                downloadPaused,
                modelDir.toString(),
                System.currentTimeMillis()
        );
    }

    private String scoreTier(int score) {
        if (score >= 8) {
            return "HIGH";
        }
        if (score <= 4) {
            return "LOW";
        }
        return "MID";
    }

    public boolean hasModelContent(TtsModelInfo info) {
        Path modelDir = resolveModelDir(info);
        if (modelDir == null || !Files.isDirectory(modelDir)) {
            return false;
        }
        if (info.modelFiles != null && !info.modelFiles.isEmpty()) {
            return info.modelFiles.stream()
                    .filter(file -> file != null && !file.isBlank())
                    .anyMatch(file -> Files.isRegularFile(modelDir.resolve(file)));
        }
        if ("moss".equals(info.getEngineType())) {
            return Files.isRegularFile(modelDir.resolve("browser_poc_manifest.json")) || hasModelContent(modelDir);
        }
        return hasModelContent(modelDir);
    }

    private boolean hasModelContent(Path modelDir) {
        if (modelDir == null || !Files.isDirectory(modelDir)) {
            return false;
        }
        try (var stream = Files.walk(modelDir)) {
            return stream.anyMatch(path -> {
                if (!Files.isRegularFile(path)) {
                    return false;
                }
                String name = path.getFileName().toString().toLowerCase();
                return name.endsWith(".onnx") || name.endsWith(".bin") || name.endsWith(".gguf") || name.endsWith(".fst");
            });
        } catch (IOException e) {
            return false;
        }
    }

    public void deleteModel(TtsModelInfo info) {
        Path modelDir = resolveModelDir(info);
        if (modelDir == null || !Files.exists(modelDir)) {
            return;
        }
        try {
            deleteRecursively(modelDir);
        } catch (IOException e) {
            env.error("删除 TTS 模型失败", e);
        }
    }

    public void downloadModel(TtsModelInfo info, String proxyUrl, DownloadProgressCallback callback) {
        if (info == null) {
            if (callback != null) {
                callback.onError("TTS 模型信息为空");
            }
            return;
        }
        downloadCancelled = false;
        downloadPaused = false;
        executorManager.submit(
                ProtocolTaskSpec.builder()
                        .moduleId("module.tts")
                        .lane(ExecutionLane.IO)
                        .concurrencyKey("module.tts:model.download")
                        .maxConcurrency(1)
                        .queueCapacity(1)
                        .build(),
                () -> runDownloadModel(info, proxyUrl, callback)
        );
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

    private void runDownloadModel(TtsModelInfo info, String proxyUrl, DownloadProgressCallback callback) {
        try {
            Path modelDir = resolveModelDir(info);
            if (modelDir == null) {
                notifyError(callback, "无法解析模型目录");
                return;
            }
            if (info.downloadUrl != null && !info.downloadUrl.isBlank()) {
                downloadArchiveModel(info, modelDir, proxyUrl, callback);
            } else if ("moss".equals(info.getEngineType())) {
                downloadMossModel(modelDir, callback);
            } else {
                downloadSherpaModel(info, modelDir, callback);
            }
            if (downloadCancelled) {
                notifyError(callback, "下载已取消");
                return;
            }
            ModelSettings.saveTtsSettings(modelDir, ModelSettings.loadTtsSettings(modelDir));
            notifyProgress(callback, "完成", 100);
            if (callback != null) {
                callback.onComplete();
            }
        } catch (Exception e) {
            notifyError(callback, e.getMessage() != null ? e.getMessage() : "下载失败");
        }
    }

    private void downloadSherpaModel(TtsModelInfo info, Path modelDir, DownloadProgressCallback callback) throws Exception {
        waitIfDownloadPaused();
        if (downloadCancelled) {
            throw new IOException("下载已取消");
        }
        notifyProgress(callback, "解析 HuggingFace 文件", 5);
        HuggingFaceDownloader downloader = new HuggingFaceDownloader(env);
        downloader.downloadModelFiles(info.id, modelDir, "main", true, 3,
                () -> {
                    if (downloadCancelled) {
                        throw new IOException("下载已取消");
                    }
                    waitIfDownloadPaused();
                },
                new HuggingFaceDownloader.DownloadProgressListener() {
                    @Override
                    public void onFileProgress(String filePath, int fileIndex, int totalFiles, long downloadedBytes, long totalBytes) {
                        int percent = totalFiles <= 0 ? 90 : Math.min(94, 5 + (int) ((fileIndex - 1L) * 80 / totalFiles));
                        notifyProgress(callback, "解析 HuggingFace 文件", percent);
                    }
                });
        if (downloadCancelled) {
            throw new IOException("下载已取消");
        }
        notifyProgress(callback, "下载完成", 95);
    }

    private void downloadMossModel(Path modelDir, DownloadProgressCallback callback) throws Exception {
        waitIfDownloadPaused();
        if (downloadCancelled) {
            throw new IOException("下载已取消");
        }
        notifyProgress(callback, "下载 MOSS 模型", 5);
        HuggingFaceDownloader downloader = new HuggingFaceDownloader(env);
        downloader.downloadModelFiles("OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX", modelDir, "main", true, 3,
                this::waitIfDownloadPaused,
                new HuggingFaceDownloader.DownloadProgressListener() {
                    @Override
                    public void onFileProgress(String filePath, int fileIndex, int totalFiles, long downloadedBytes, long totalBytes) {
                        int percent = totalFiles <= 0 ? 45 : Math.min(48, 5 + (int) ((fileIndex - 1L) * 40 / totalFiles));
                        notifyProgress(callback, "下载 MOSS 模型", percent);
                    }
                });
        if (downloadCancelled) {
            throw new IOException("下载已取消");
        }
        downloader.downloadModelFiles("OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX", modelDir, "main", true, 3,
                this::waitIfDownloadPaused,
                new HuggingFaceDownloader.DownloadProgressListener() {
                    @Override
                    public void onFileProgress(String filePath, int fileIndex, int totalFiles, long downloadedBytes, long totalBytes) {
                        int percent = totalFiles <= 0 ? 90 : Math.min(94, 50 + (int) ((fileIndex - 1L) * 40 / totalFiles));
                        notifyProgress(callback, "下载 MOSS 模型", percent);
                    }
                });
        if (downloadCancelled) {
            throw new IOException("下载已取消");
        }
        notifyProgress(callback, "下载完成", 95);
    }

    private void downloadArchiveModel(TtsModelInfo info, Path modelDir, String proxyUrl, DownloadProgressCallback callback) throws Exception {
        Files.createDirectories(modelDir.getParent());
        String archiveName = archiveName(info.downloadUrl);
        Path archivePath = modelDir.getParent().resolve(archiveName);
        String finalUrl = buildDownloadUrl(info.downloadUrl, proxyUrl);

        notifyProgress(callback, "下载压缩包", 5);
        downloadFile(finalUrl, archivePath, 5, 60_000, (downloaded, total) -> {
            int percent = total > 0 ? Math.min(85, (int) (downloaded * 80 / total) + 5) : 40;
            notifyProgress(callback, "下载压缩包", percent);
        });

        if (downloadCancelled) {
            throw new IOException("下载已取消");
        }
        waitIfDownloadPaused();
        notifyProgress(callback, "解压模型", 90);
        Path tempDir = modelDir.getParent().resolve(modelDir.getFileName().toString() + "-extract");
        deleteRecursivelyIfExists(tempDir);
        Files.createDirectories(tempDir);
        extractTarBz2(archivePath, tempDir);

        Path extractedModelDir = resolveExtractedModelDir(tempDir, info);
        deleteRecursivelyIfExists(modelDir);
        Files.move(extractedModelDir, modelDir, StandardCopyOption.REPLACE_EXISTING);
        deleteRecursivelyIfExists(tempDir);
        Files.deleteIfExists(archivePath);
        notifyProgress(callback, "解压完成", 95);
    }

    private Path resolveExtractedModelDir(Path extractedRoot, TtsModelInfo info) throws IOException {
        if (info != null && info.archiveSubDir != null && !info.archiveSubDir.isBlank()) {
            Path archived = extractedRoot.resolve(info.archiveSubDir).normalize();
            if (!archived.startsWith(extractedRoot.normalize())) {
                throw new IOException("非法模型子目录: " + info.archiveSubDir);
            }
            if (Files.isDirectory(archived)) {
                return archived;
            }
        }
        try (var stream = Files.list(extractedRoot)) {
            List<Path> entries = stream.toList();
            if (entries.size() == 1 && Files.isDirectory(entries.get(0))) {
                return entries.get(0);
            }
        }
        return extractedRoot;
    }

    private void notifyProgress(DownloadProgressCallback callback, String label, int percent) {
        if (callback != null) {
            callback.onProgress(label, percent);
        }
    }

    private void notifyError(DownloadProgressCallback callback, String message) {
        if (callback != null) {
            callback.onError(message);
        }
    }

    private String buildDownloadUrl(String downloadUrl, String proxyUrl) {
        if (proxyUrl == null || proxyUrl.isBlank()) {
            return downloadUrl;
        }
        String normalizedProxy = proxyUrl.endsWith("/") ? proxyUrl : proxyUrl + "/";
        if (downloadUrl.startsWith(normalizedProxy)) {
            return downloadUrl;
        }
        return normalizedProxy + downloadUrl;
    }

    private String archiveName(String downloadUrl) {
        int idx = downloadUrl.lastIndexOf('/');
        return idx >= 0 ? downloadUrl.substring(idx + 1) : "model.tar.bz2";
    }

    private void extractTarBz2(Path archive, Path targetDir) throws IOException {
        try (InputStream fis = Files.newInputStream(archive);
             InputStream bis = new java.io.BufferedInputStream(fis);
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

    private interface ProgressListener {
        void onProgress(long downloaded, long total);
    }

    private void downloadFile(String urlString, Path targetPath, int maxRetries, int timeoutMillis, ProgressListener listener) throws IOException {
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
                conn.setRequestProperty("User-Agent", "Tianshu-Downloader/1.0");
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
                        if (downloadCancelled) {
                            throw new IOException("下载已取消");
                        }
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
                if (downloadCancelled) {
                    throw e;
                }
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        throw last != null ? last : new IOException("下载失败");
    }

    private void waitIfDownloadPaused() throws IOException {
        while (downloadPaused) {
            if (downloadCancelled) {
                throw new IOException("下载已取消");
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("下载线程被中断", e);
            }
        }
        if (downloadCancelled) {
            throw new IOException("下载已取消");
        }
    }

    private void deleteRecursivelyIfExists(Path path) throws IOException {
        if (Files.exists(path)) {
            deleteRecursively(path);
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
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
            if (e.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw e;
        }
    }
}
