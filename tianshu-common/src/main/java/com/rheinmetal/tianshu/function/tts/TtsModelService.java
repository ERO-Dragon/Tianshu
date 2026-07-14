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
import com.rheinmetal.tianshu.protocol.status.ModuleStatuses;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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
    private final Consumer<ModuleStatus> moduleStatusSink;
    private final AtomicReference<DownloadTask> activeDownload = new AtomicReference<>();
    private final AtomicReference<DownloadStatus> downloadStatus = new AtomicReference<>(DownloadStatus.idle());
    private final AtomicBoolean deletingModel = new AtomicBoolean(false);

    public TtsModelService(IGameEnvironment env, ITianshuConfig config, ProtocolExecutorManager executorManager) {
        this(env, config, executorManager, null);
    }

    public TtsModelService(IGameEnvironment env, ITianshuConfig config, ProtocolExecutorManager executorManager, Consumer<ModuleStatus> moduleStatusSink) {
        this.env = env;
        this.config = config;
        this.executorManager = executorManager;
        this.moduleStatusSink = moduleStatusSink == null ? ignored -> {} : moduleStatusSink;
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
            env.error("tts.model.settings.save_failed", e);
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
            env.warn("tts.download.cleanup_failed: " + e.getMessage());
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
            env.error("tts.model.delete_failed", e);
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
                callback.onError("tianshu.gui.tts.failure.invalid_request");
            }
            return;
        }
        Path modelDir = resolveModelDir(info);
        if (modelDir == null) {
            if (callback != null) {
                callback.onError("tianshu.gui.tts.failure.invalid_request");
            }
            return;
        }
        DownloadTask task = new DownloadTask(safeModelName(info), modelDir, hasModelContent(info), downloadCoordinator.newSession());
        if (!activeDownload.compareAndSet(null, task)) {
            publishFailed("tianshu.presence.module.tts.download_busy", "");
            if (callback != null) {
                callback.onError("tianshu.gui.tts.failure.download_busy");
            }
            return;
        }
        updateDownload(true, false, false, task.modelName(), "tianshu.gui.tts.status.download_preparing", 0);
        publishWaiting("tianshu.presence.module.tts.download_started", "");
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
            updateDownload(false, false, false, task.modelName(), "tianshu.gui.tts.failure.download_queue_full", 0);
            publishFailed("tianshu.presence.module.tts.download_queue_full", "");
            if (callback != null) {
                callback.onError("tianshu.gui.tts.failure.download_queue_full");
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
        publishWaiting("tianshu.presence.module.tts.download_paused", "");
    }

    public void resumeDownload() {
        DownloadTask task = activeDownload.get();
        DownloadStatus current = downloadStatus.get();
        if (task == null || current.cancelling()) {
            return;
        }
        task.session().resume();
        updateDownload(true, false, false, task.modelName(), current.label(), current.progress());
        publishWaiting("tianshu.presence.module.tts.download_resumed", "");
    }

    public void cancelDownload() {
        DownloadTask task = activeDownload.get();
        DownloadStatus current = downloadStatus.get();
        if (task == null) {
            return;
        }
        task.session().cancel();
        updateDownload(true, false, true, task.modelName(), "tianshu.gui.tts.status.cancelling", current.progress());
        publishWaiting("tianshu.presence.module.tts.download_cancelling", "");
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

            if (info.downloadUri != null && !info.downloadUri.isBlank()) {
                downloadArchiveModel(task, info, stagingDir, proxyUrl, callback);
            } else if ("moss".equals(info.getEngineType())) {
                downloadMossModel(task, stagingDir, callback);
            } else {
                downloadSherpaModel(task, info, stagingDir, callback);
            }
            task.session().awaitReady();
            if (!TtsModelInfo.isModelDirectoryComplete(info, stagingDir)) {
                throw new IOException("tts.download.model_incomplete: " + info.getDisplayName());
            }
            ModelSettings.saveTtsSettings(stagingDir, ModelSettings.loadTtsSettings(stagingDir));
            commitStagingDownload(stagingDir, task.modelDir());
            finishDownloadComplete(task, callback);
        } catch (Exception e) {
            if (task.session().isCancelled() || isDownloadCancelled(e)) {
                finishDownloadCancelled(task, callback);
            } else {
                env.error("tts.download.failed", e);
                finishDownloadError(task, callback, "tianshu.gui.tts.status.download_failed");
            }
        }
    }

    private void downloadSherpaModel(DownloadTask task, TtsModelInfo info, Path modelDir, DownloadProgressCallback callback) throws Exception {
        task.session().awaitReady();
        emitProgress(task, callback, "tianshu.gui.tts.status.download_resolving", 5);
        task.session().downloadModelFiles(info.id, modelDir, "main", true, 3,
                new com.rheinmetal.tianshu.model.HuggingFaceDownloader.DownloadProgressListener() {
                    @Override
                    public void onFileProgress(String filePath, int fileIndex, int totalFiles, long downloadedBytes, long totalBytes) {
                        int percent = totalFiles <= 0 ? 90 : Math.min(94, 5 + (int) ((fileIndex - 1L) * 80 / totalFiles));
                        emitProgress(task, callback, "tianshu.gui.tts.status.download_resolving", percent);
                    }
                });
        task.session().awaitReady();
        emitProgress(task, callback, "tianshu.gui.tts.status.download_complete", 95);
    }

    private void downloadMossModel(DownloadTask task, Path modelDir, DownloadProgressCallback callback) throws Exception {
        task.session().awaitReady();
        emitProgress(task, callback, "tianshu.gui.tts.status.downloading", 5);
        task.session().downloadModelFiles("OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX", modelDir, "main", true, 3,
                new com.rheinmetal.tianshu.model.HuggingFaceDownloader.DownloadProgressListener() {
                    @Override
                    public void onFileProgress(String filePath, int fileIndex, int totalFiles, long downloadedBytes, long totalBytes) {
                        int percent = totalFiles <= 0 ? 45 : Math.min(48, 5 + (int) ((fileIndex - 1L) * 40 / totalFiles));
                        emitProgress(task, callback, "tianshu.gui.tts.status.downloading", percent);
                    }
                });
        task.session().awaitReady();
        task.session().downloadModelFiles("OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX", modelDir, "main", true, 3,
                new com.rheinmetal.tianshu.model.HuggingFaceDownloader.DownloadProgressListener() {
                    @Override
                    public void onFileProgress(String filePath, int fileIndex, int totalFiles, long downloadedBytes, long totalBytes) {
                        int percent = totalFiles <= 0 ? 90 : Math.min(94, 50 + (int) ((fileIndex - 1L) * 40 / totalFiles));
                        emitProgress(task, callback, "tianshu.gui.tts.status.downloading", percent);
                    }
                });
        task.session().awaitReady();
        emitProgress(task, callback, "tianshu.gui.tts.status.download_complete", 95);
    }

    private void downloadArchiveModel(DownloadTask task, TtsModelInfo info, Path modelDir, String proxyUrl, DownloadProgressCallback callback) throws Exception {
        Files.createDirectories(modelDir);
        URI archiveUri = requireHttpUri(info.downloadUri, "tts.download.invalid_archive_uri");
        URI proxyBaseUri = optionalHttpUri(proxyUrl, "tts.download.invalid_proxy_uri");
        String archiveName = archiveName(archiveUri);
        Path archivePath = modelDir.resolve(archiveName);

        emitProgress(task, callback, "tianshu.gui.tts.status.downloading", 5);
        task.session().downloadArchive(archiveUri, proxyBaseUri, proxyBaseUri != null, archivePath, 5, 60_000, (downloaded, total) -> {
            int percent = total > 0 ? Math.min(85, (int) (downloaded * 80 / total) + 5) : 40;
            emitProgress(task, callback, "tianshu.gui.tts.status.downloading", percent);
        });

        task.session().awaitReady();
        emitProgress(task, callback, "tianshu.gui.tts.status.download_extracting", 90);
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
        emitProgress(task, callback, "tianshu.gui.tts.status.download_complete", 95);
    }

    private Path resolveExtractedModelDir(Path extractedRoot, TtsModelInfo info) throws IOException {
        if (info != null && info.archiveSubDir != null && !info.archiveSubDir.isBlank()) {
            Path archived = extractedRoot.resolve(info.archiveSubDir).normalize();
            if (!archived.startsWith(extractedRoot.normalize())) {
                throw new IOException("tts.download.invalid_archive_subdirectory: " + info.archiveSubDir);
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

    private URI requireHttpUri(String value, String errorCode) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IOException(errorCode);
        }
        try {
            URI uri = URI.create(value.trim());
            if (!uri.isAbsolute() || uri.getHost() == null
                    || (!("http".equalsIgnoreCase(uri.getScheme())) && !("https".equalsIgnoreCase(uri.getScheme())))) {
                throw new IllegalArgumentException("URI must use HTTP(S)");
            }
            return uri;
        } catch (IllegalArgumentException failure) {
            throw new IOException(errorCode, failure);
        }
    }

    private URI optionalHttpUri(String value, String errorCode) throws IOException {
        return value == null || value.isBlank() ? null : requireHttpUri(value, errorCode);
    }

    private String archiveName(URI archiveUri) {
        String path = archiveUri == null ? "" : archiveUri.getPath();
        if (path == null || path.isBlank() || path.endsWith("/")) {
            return "model.tar.bz2";
        }
        int index = path.lastIndexOf('/');
        String name = index >= 0 ? path.substring(index + 1) : path;
        return name.isBlank() ? "model.tar.bz2" : name;
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
                    throw new IOException("tts.download.invalid_archive_path: " + entry.getName());
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
        updateDownload(false, false, false, task.modelName(), "tianshu.gui.tts.status.download_complete", 100);
        publishReady("tianshu.presence.module.tts.download_complete", "");
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
        updateDownload(false, false, false, task.modelName(), "tianshu.gui.tts.status.cancelled", progress);
        publishReady("tianshu.presence.module.tts.download_cancelled", "");
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
        publishFailed("tianshu.presence.module.tts.download_failed", "");
        if (callback != null) {
            callback.onError(message);
        }
    }

    private void publishReady(String messageKey, String fallbackTitle) {
        moduleStatusSink.accept(ModuleStatuses.readyKeyed("module.tts", messageKey, fallbackTitle));
    }

    private void publishWaiting(String messageKey, String fallbackTitle) {
        moduleStatusSink.accept(ModuleStatuses.waitingKeyed("module.tts", messageKey, fallbackTitle));
    }

    private void publishFailed(String messageKey, String fallbackTitle) {
        moduleStatusSink.accept(ModuleStatuses.failedKeyed("module.tts", messageKey, fallbackTitle));
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
            env.warn("tts.download.cleanup_failed: " + e.getMessage());
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


