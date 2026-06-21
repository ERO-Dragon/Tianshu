package com.rheinmetal.tianshu.function.tts;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.function.tts.download.TtsModelDownloadCoordinator;
import com.rheinmetal.tianshu.function.tts.runtime.TtsModelSnapshot;
import com.rheinmetal.tianshu.model.ModelSettings;
import com.rheinmetal.tianshu.model.TtsModelInfo;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutorManager;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskState;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class TtsModelService {

    public interface DownloadProgressCallback {
        void onProgress(String label, int percent);
        void onComplete();
        void onError(String message);

        default void onCancelled() {
        }
    }

    public interface ModelDeleteCallback {
        void onComplete(boolean deleted);
    }

    public record DownloadStatus(boolean downloading, boolean paused, boolean cancelling, String activeModelName, String label, int progress) {
        public static DownloadStatus idle() {
            return new DownloadStatus(false, false, false, "", "", 0);
        }
    }

    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final ProtocolExecutorManager executorManager;
    private final TtsModelDownloadCoordinator downloadCoordinator;
    private final AtomicReference<DownloadTask> activeDownload = new AtomicReference<>();
    private final AtomicReference<DownloadStatus> downloadStatus = new AtomicReference<>(DownloadStatus.idle());
    private final AtomicBoolean deletingModel = new AtomicBoolean(false);

    public TtsModelService(IGameEnvironment env, ITianshuConfig config, ProtocolExecutorManager executorManager) {
        this.env = env;
        this.config = config;
        this.executorManager = executorManager;
        this.downloadCoordinator = new TtsModelDownloadCoordinator(env);
        scheduleStartupCleanup();
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
        return configured == null ? "" : configured.trim();
    }

    public void useModel(String modelName) {
        if (modelName != null && !modelName.isBlank()) {
            config.setCustomTtsName(modelName.trim());
        }
    }

    public void clearModel() {
        config.setCustomTtsName("");
    }

    public ModelSettings.TtsSettings loadSettings(TtsModelInfo info) {
        Path modelDir = info == null ? resolveCurrentModelDir() : resolveModelDir(info);
        return modelDir == null ? new ModelSettings.TtsSettings() : ModelSettings.loadTtsSettings(modelDir);
    }

    public Path resolveVoiceSamplePath(TtsModelInfo info, String sampleNameOrPath) {
        if (info == null) {
            return null;
        }
        String normalized = sampleNameOrPath == null ? "" : sampleNameOrPath.trim();
        if (normalized.isBlank()) {
            normalized = info.defaultVoiceSample == null ? "" : info.defaultVoiceSample.trim();
        }
        if (normalized.isBlank()) {
            return null;
        }
        Path modelDir = resolveModelDir(info);
        if (modelDir == null) {
            return null;
        }
        Path fileName = Path.of(normalized).getFileName();
        if (fileName == null) {
            return null;
        }
        Path resolved = modelDir.resolve(fileName.toString()).normalize();
        Path root = modelDir.normalize();
        if (resolved.startsWith(root) && Files.isRegularFile(resolved)) {
            return resolved;
        }
        return null;
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
        String configured = currentConfiguredModelName();
        if (configured.isBlank()) {
            return null;
        }
        Path modelPath = config.getTtsBasePath().resolve("model").resolve(configured);
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
        String configured = currentConfiguredModelName();
        if (configured.isBlank()) {
            return null;
        }
        TtsModelInfo info = resolveCurrentModelInfo();
        if (info != null) {
            return resolveModelDir(info);
        }
        return config.getTtsBasePath().resolve("model").resolve(configured);
    }

    public Path resolveModelDir(TtsModelInfo info) {
        if (info == null || info.name == null) {
            return null;
        }
        String modelDirName = "zipvoice".equals(info.getEngineType()) ? "ZipVoice" : info.name;
        return config.getTtsBasePath().resolve("model").resolve(modelDirName);
    }

    private Path modelBasePath() {
        return config.getTtsBasePath().resolve("model");
    }

    private void scheduleStartupCleanup() {
        executorManager.submit(
                ProtocolTaskSpec.builder()
                        .moduleId("module.tts")
                        .lane(ExecutionLane.IO)
                        .concurrencyKey("module.tts:model.cleanup")
                        .maxConcurrency(1)
                        .queueCapacity(1)
                        .build(),
                this::cleanupStaleDownloadArtifacts
        );
    }

    private void cleanupStaleDownloadArtifacts() {
        Path base = modelBasePath();
        if (!Files.isDirectory(base)) {
            return;
        }
        try (var walk = Files.walk(base)) {
            List<Path> paths = walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList();
            for (Path path : paths) {
                String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase();
                if (Files.isRegularFile(path) && (name.endsWith(".tmp") || name.endsWith(".downloading"))) {
                    Files.deleteIfExists(path);
                } else if (Files.isDirectory(path) && (name.endsWith("-extract") || name.endsWith("-staging"))) {
                    deleteRecursively(path);
                }
            }
        } catch (IOException e) {
            env.warn("清理未完成的 TTS 模型下载失败: " + e.getMessage());
        }
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
                isDownloadPaused(),
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
        return TtsModelInfo.isModelDirectoryComplete(info, modelDir);
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

    public boolean deleteModel(TtsModelInfo info) {
        Path modelDir = resolveModelDir(info);
        if (modelDir == null || !Files.exists(modelDir)) {
            return false;
        }
        try {
            deleteRecursively(modelDir);
            return true;
        } catch (IOException e) {
            env.error("删除 TTS 模型失败", e);
            return false;
        }
    }

    public void deleteModelAsync(TtsModelInfo info, ModelDeleteCallback callback) {
        if (info == null) {
            if (callback != null) {
                callback.onComplete(false);
            }
            return;
        }
        if (!deletingModel.compareAndSet(false, true)) {
            if (callback != null) {
                callback.onComplete(false);
            }
            return;
        }
        ProtocolTaskHandle handle = executorManager.submit(
                ProtocolTaskSpec.builder()
                        .moduleId("module.tts")
                        .lane(ExecutionLane.IO)
                        .concurrencyKey("module.tts:model.delete")
                        .maxConcurrency(1)
                        .queueCapacity(2)
                        .build(),
                () -> {
                    boolean deleted = false;
                    try {
                        deleted = deleteModel(info);
                    } finally {
                        deletingModel.set(false);
                    }
                    if (callback != null) {
                        callback.onComplete(deleted);
                    }
                }
        );
        if (handle.state() == ProtocolTaskState.REJECTED) {
            deletingModel.set(false);
            if (callback != null) {
                callback.onComplete(false);
            }
        }
    }

    public boolean isDeleting() {
        return deletingModel.get();
    }

    public void downloadModel(TtsModelInfo info, String proxyUrl, DownloadProgressCallback callback) {
        if (info == null) {
            if (callback != null) {
                callback.onError("TTS 模型信息为空");
            }
            return;
        }
        Path modelDir = resolveModelDir(info);
        if (modelDir == null) {
            if (callback != null) {
                callback.onError("无法解析模型目录");
            }
            return;
        }
        DownloadTask task = new DownloadTask(safeModelName(info), modelDir, hasModelContent(info), downloadCoordinator.newSession());
        if (!activeDownload.compareAndSet(null, task)) {
            if (callback != null) {
                callback.onError("已有 TTS 模型正在下载");
            }
            return;
        }
        updateDownload(true, false, false, task.modelName(), "Preparing", 0);
        ProtocolTaskHandle handle = executorManager.submit(
                ProtocolTaskSpec.builder()
                        .moduleId("module.tts")
                        .lane(ExecutionLane.IO)
                        .concurrencyKey("module.tts:model.download")
                        .maxConcurrency(1)
                        .queueCapacity(1)
                        .build(),
                () -> runDownloadModel(task, info, proxyUrl, callback)
        );
        if (handle.state() == ProtocolTaskState.REJECTED) {
            activeDownload.compareAndSet(task, null);
            updateDownload(false, false, false, task.modelName(), "TTS model download queue is full", 0);
            if (callback != null) {
                callback.onError("TTS model download queue is full");
            }
        }
    }

    public DownloadStatus downloadStatus() {
        return downloadStatus.get();
    }

    public boolean isDownloadPaused() {
        DownloadTask task = activeDownload.get();
        return task != null && task.session().isPaused();
    }

    public void pauseDownload() {
        DownloadTask task = activeDownload.get();
        DownloadStatus current = downloadStatus.get();
        if (task == null || current.cancelling()) {
            return;
        }
        task.session().pause();
        updateDownload(true, true, false, task.modelName(), current.label(), current.progress());
    }

    public void resumeDownload() {
        DownloadTask task = activeDownload.get();
        DownloadStatus current = downloadStatus.get();
        if (task == null || current.cancelling()) {
            return;
        }
        task.session().resume();
        updateDownload(true, false, false, task.modelName(), current.label(), current.progress());
    }

    public void cancelDownload() {
        DownloadTask task = activeDownload.get();
        DownloadStatus current = downloadStatus.get();
        if (task == null) {
            return;
        }
        task.session().cancel();
        updateDownload(true, false, true, task.modelName(), "Cancelling", current.progress());
    }

    private void runDownloadModel(DownloadTask task, TtsModelInfo info, String proxyUrl, DownloadProgressCallback callback) {
        try {
            if (task.hadModelContent()) {
                finishDownloadComplete(task, callback);
                return;
            }
            Path stagingDir = stagingDir(task.modelDir());
            deleteRecursivelyIfExists(stagingDir);
            Files.createDirectories(stagingDir);

            if (info.downloadUrl != null && !info.downloadUrl.isBlank()) {
                downloadArchiveModel(task, info, stagingDir, proxyUrl, callback);
            } else if ("moss".equals(info.getEngineType())) {
                downloadMossModel(task, stagingDir, callback);
            } else {
                downloadSherpaModel(task, info, stagingDir, callback);
            }
            task.session().awaitReady();
            if (!TtsModelInfo.isModelDirectoryComplete(info, stagingDir)) {
                throw new IOException("TTS 模型下载完成但文件不完整: " + info.getDisplayName());
            }
            ModelSettings.saveTtsSettings(stagingDir, ModelSettings.loadTtsSettings(stagingDir));
            commitStagingDownload(stagingDir, task.modelDir());
            finishDownloadComplete(task, callback);
        } catch (Exception e) {
            if (task.session().isCancelled() || isDownloadCancelled(e)) {
                finishDownloadCancelled(task, callback);
            } else {
                finishDownloadError(task, callback, e.getMessage() != null ? e.getMessage() : "下载失败");
            }
        }
    }

    private void downloadSherpaModel(DownloadTask task, TtsModelInfo info, Path modelDir, DownloadProgressCallback callback) throws Exception {
        task.session().awaitReady();
        emitProgress(task, callback, "解析 HuggingFace 文件", 5);
        task.session().downloadModelFiles(info.id, modelDir, "main", true, 3,
                new com.rheinmetal.tianshu.model.HuggingFaceDownloader.DownloadProgressListener() {
                    @Override
                    public void onFileProgress(String filePath, int fileIndex, int totalFiles, long downloadedBytes, long totalBytes) {
                        int percent = totalFiles <= 0 ? 90 : Math.min(94, 5 + (int) ((fileIndex - 1L) * 80 / totalFiles));
                        emitProgress(task, callback, "解析 HuggingFace 文件", percent);
                    }
                });
        task.session().awaitReady();
        emitProgress(task, callback, "下载完成", 95);
    }

    private void downloadMossModel(DownloadTask task, Path modelDir, DownloadProgressCallback callback) throws Exception {
        task.session().awaitReady();
        emitProgress(task, callback, "下载 MOSS 模型", 5);
        task.session().downloadModelFiles("OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX", modelDir, "main", true, 3,
                new com.rheinmetal.tianshu.model.HuggingFaceDownloader.DownloadProgressListener() {
                    @Override
                    public void onFileProgress(String filePath, int fileIndex, int totalFiles, long downloadedBytes, long totalBytes) {
                        int percent = totalFiles <= 0 ? 45 : Math.min(48, 5 + (int) ((fileIndex - 1L) * 40 / totalFiles));
                        emitProgress(task, callback, "下载 MOSS 模型", percent);
                    }
                });
        task.session().awaitReady();
        task.session().downloadModelFiles("OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX", modelDir, "main", true, 3,
                new com.rheinmetal.tianshu.model.HuggingFaceDownloader.DownloadProgressListener() {
                    @Override
                    public void onFileProgress(String filePath, int fileIndex, int totalFiles, long downloadedBytes, long totalBytes) {
                        int percent = totalFiles <= 0 ? 90 : Math.min(94, 50 + (int) ((fileIndex - 1L) * 40 / totalFiles));
                        emitProgress(task, callback, "下载 MOSS 模型", percent);
                    }
                });
        task.session().awaitReady();
        emitProgress(task, callback, "下载完成", 95);
    }

    private void downloadArchiveModel(DownloadTask task, TtsModelInfo info, Path modelDir, String proxyUrl, DownloadProgressCallback callback) throws Exception {
        Files.createDirectories(modelDir);
        String archiveName = archiveName(info.downloadUrl);
        Path archivePath = modelDir.resolve(archiveName);
        String finalUrl = buildDownloadUrl(info.downloadUrl, proxyUrl);

        emitProgress(task, callback, "下载压缩包", 5);
        task.session().downloadArchive(finalUrl, archivePath, 5, 60_000, (downloaded, total) -> {
            int percent = total > 0 ? Math.min(85, (int) (downloaded * 80 / total) + 5) : 40;
            emitProgress(task, callback, "下载压缩包", percent);
        });

        task.session().awaitReady();
        emitProgress(task, callback, "解压模型", 90);
        Path tempDir = modelDir.resolveSibling(modelDir.getFileName().toString() + "-extract");
        deleteRecursivelyIfExists(tempDir);
        Files.createDirectories(tempDir);
        extractTarBz2(archivePath, tempDir);

        Path extractedModelDir = resolveExtractedModelDir(tempDir, info);
        deleteRecursivelyIfExists(modelDir);
        Files.move(extractedModelDir, modelDir, StandardCopyOption.REPLACE_EXISTING);
        deleteRecursivelyIfExists(tempDir);
        Files.deleteIfExists(archivePath);
        task.session().awaitReady();
        emitProgress(task, callback, "解压完成", 95);
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

    private void emitProgress(DownloadTask task, DownloadProgressCallback callback, String label, int percent) {
        if (!isCurrentTask(task) || task.session().isCancelled()) {
            return;
        }
        updateDownload(true, task.session().isPaused(), false, task.modelName(), label, percent);
        if (callback != null) {
            callback.onProgress(label, percent);
        }
    }

    private void updateDownload(boolean downloading, boolean paused, boolean cancelling, String modelName, String label, int percent) {
        downloadStatus.set(new DownloadStatus(downloading, paused, cancelling, modelName, label == null ? "" : label, Math.max(0, Math.min(100, percent))));
    }

    private String safeModelName(TtsModelInfo info) {
        return info == null || info.name == null ? "" : info.name;
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

    private Path stagingDir(Path modelDir) {
        return modelDir.resolveSibling(modelDir.getFileName().toString() + "-staging");
    }

    private void commitStagingDownload(Path stagingDir, Path modelDir) throws IOException {
        deleteRecursivelyIfExists(modelDir);
        Files.move(stagingDir, modelDir, StandardCopyOption.REPLACE_EXISTING);
    }

    private boolean isCurrentTask(DownloadTask task) {
        return task != null && activeDownload.get() == task;
    }

    private boolean finishTask(DownloadTask task) {
        return task != null && activeDownload.compareAndSet(task, null);
    }

    private void finishDownloadComplete(DownloadTask task, DownloadProgressCallback callback) {
        if (!finishTask(task)) {
            return;
        }
        updateDownload(false, false, false, task.modelName(), "Complete", 100);
        if (callback != null) {
            callback.onComplete();
        }
    }

    private void finishDownloadCancelled(DownloadTask task, DownloadProgressCallback callback) {
        if (!finishTask(task)) {
            return;
        }
        cleanupIncompleteDownload(task);
        int progress = downloadStatus.get().progress();
        updateDownload(false, false, false, task.modelName(), "下载已取消", progress);
        if (callback != null) {
            callback.onCancelled();
        }
    }

    private void finishDownloadError(DownloadTask task, DownloadProgressCallback callback, String message) {
        if (!finishTask(task)) {
            return;
        }
        cleanupIncompleteDownload(task);
        int progress = downloadStatus.get().progress();
        updateDownload(false, false, false, task.modelName(), message, progress);
        if (callback != null) {
            callback.onError(message);
        }
    }

    private void cleanupIncompleteDownload(DownloadTask task) {
        if (task == null || task.hadModelContent()) {
            return;
        }
        Path stagingDir = stagingDir(task.modelDir());
        try {
            deleteRecursivelyIfExists(stagingDir);
            deleteRecursivelyIfExists(stagingDir.resolveSibling(stagingDir.getFileName().toString() + "-extract"));
        } catch (IOException e) {
            env.warn("清理未完成的 TTS 模型下载失败: " + e.getMessage());
        }
    }

    private boolean isDownloadCancelled(Exception exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof TtsModelDownloadCoordinator.DownloadCancelledException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private record DownloadTask(
            String modelName,
            Path modelDir,
            boolean hadModelContent,
            TtsModelDownloadCoordinator.DownloadSession session
    ) {
        private DownloadTask {
            modelName = modelName == null ? "" : modelName.trim();
        }
    }
}
