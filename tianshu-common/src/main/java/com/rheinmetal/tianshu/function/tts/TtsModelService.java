package com.rheinmetal.tianshu.function.tts;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.event.TtsPlaybackEndEvent;
import com.rheinmetal.tianshu.model.HuggingFaceDownloader;
import com.rheinmetal.tianshu.model.ModelManager;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public class TtsModelService {

    public interface DownloadProgressCallback {
        void onProgress(String label, int percent);
        void onComplete();
        void onError(String message);
    }

    public interface PreviewCallback {
        void onReady();
        void onPlaying();
        void onError(String message);
        void onFinish();
    }

    public interface PlaybackEndPublisher {
        void publish(TtsPlaybackEndEvent event);
    }

    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final IAudioBridge audioBridge;
    private final ProtocolExecutorManager executorManager;
    private final Supplier<TtsWorker> workerSupplier;
    private final Runnable interruptProcessing;
    private final PlaybackEndPublisher playbackEndPublisher;
    private final AtomicBoolean previewRunning = new AtomicBoolean(false);
    private volatile boolean downloadCancelled = false;
    private volatile boolean downloadPaused = false;

    public TtsModelService(IGameEnvironment env, ITianshuConfig config, IAudioBridge audioBridge, ProtocolExecutorManager executorManager, Supplier<TtsWorker> workerSupplier, Runnable interruptProcessing, PlaybackEndPublisher playbackEndPublisher) {
        this.env = env;
        this.config = config;
        this.audioBridge = audioBridge;
        this.executorManager = executorManager;
        this.workerSupplier = workerSupplier;
        this.interruptProcessing = interruptProcessing;
        this.playbackEndPublisher = playbackEndPublisher;
    }

    public TtsModelInfo resolveCurrentModelInfo() {
        Path modelPath = config.getTtsModelPath();
        if (modelPath == null || modelPath.getFileName() == null) {
            return null;
        }
        String dirName = modelPath.getFileName().toString();
        List<TtsModelInfo> matchedZipVoice = new ArrayList<>();
        for (TtsModelInfo info : ModelManager.loadTtsModelCatalog()) {
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

    public boolean hasModelContent(TtsModelInfo info) {
        Path modelDir = resolveModelDir(info);
        if (modelDir == null || !Files.exists(modelDir)) {
            return false;
        }
        try (var stream = Files.list(modelDir)) {
            return stream.findAny().isPresent();
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
            callback.onError("TTS 模型信息为空");
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

    public void preview(String text, float speed, TtsModelInfo info, PreviewCallback callback) {
        TtsWorker worker = workerSupplier.get();
        if (worker == null || !worker.isEngineInitialized()) {
            callback.onError("TTS 引擎未就绪，请先下载并加载模型");
            callback.onFinish();
            return;
        }
        if (!previewRunning.compareAndSet(false, true)) {
            callback.onError("TTS 试听正在播放中，请等待完成");
            callback.onFinish();
            return;
        }
        submitSynthesisTask(worker, () -> runPreview(worker, text, speed, callback));
    }

    public void speakAlert(String text, boolean interruptCurrent) {
        TtsWorker worker = workerSupplier.get();
        if (worker == null || !worker.isEngineInitialized()) return;
        if (interruptCurrent) {
            interruptProcessing.run();
        }
        submitSynthesisTask(worker, () -> {
            try {
                env.info(interruptCurrent ? "[战术雷达] TTS打断播报: " + text : "[战术雷达] TTS排队播报: " + text);
                audioBridge.setOnPlaybackFinished(() -> playbackEndPublisher.publish(new TtsPlaybackEndEvent("acoustic_radar")));
                audioBridge.startTtsPlayback(worker.getSampleRate());
                worker.synthesizeForPreview(text, 1.2f, audioBridge::feedTtsAudio);
                audioBridge.finishTtsPlayback();
            } catch (Exception e) {
                env.error(interruptCurrent ? "[战术雷达] TTS打断播报失败" : "[战术雷达] TTS排队播报失败", e);
                audioBridge.stopTtsPlayback();
            }
        });
    }

    public boolean isPreviewRunning() {
        return previewRunning.get();
    }

    public void stopPreview() {
        if (!previewRunning.getAndSet(false)) {
            return;
        }
        try {
            audioBridge.stopTtsPlayback();
        } catch (Throwable ignored) {}
    }

    private void submitSynthesisTask(TtsWorker worker, Runnable task) {
        ExecutionLane lane = worker.currentSynthesisLane();
        executorManager.submit(
                ProtocolTaskSpec.builder()
                        .moduleId("module.tts")
                        .lane(lane)
                        .concurrencyKey("module.tts:synthesis:" + (lane == ExecutionLane.TTS_AUTOREGRESSIVE ? "autoregressive" : "fast"))
                        .maxConcurrency(1)
                        .queueCapacity(lane == ExecutionLane.TTS_AUTOREGRESSIVE ? 1 : 4)
                        .build(),
                task
        );
    }

    private void runPreview(TtsWorker worker, String text, float speed, PreviewCallback callback) {
        try {
            env.info("TTS 试听: 开始合成，文本=" + text);
            callback.onReady();

            audioBridge.startTtsPlayback(worker.getSampleRate());
            callback.onPlaying();
            worker.synthesizeForPreview(text, speed, audio -> {
                if (!previewRunning.get()) return;
                audioBridge.feedTtsAudio(audio);
            });

            if (previewRunning.get()) {
                audioBridge.finishTtsPlayback();
                env.info("TTS 试听: 播放完成");
            }
        } catch (Exception e) {
            env.error("TTS 试听失败", e);
            callback.onError("TTS 试听失败: " + e.getMessage());
        } finally {
            audioBridge.stopTtsPlayback();
            previewRunning.set(false);
            callback.onFinish();
        }
    }

    private void runDownloadModel(TtsModelInfo info, String proxyUrl, DownloadProgressCallback callback) {
        try {
            Path modelDir = resolveModelDir(info);
            if (modelDir == null) {
                callback.onError("无法解析模型目录");
                return;
            }
            if (info.downloadUrl != null && !info.downloadUrl.isBlank()) {
                downloadArchiveModel(info, modelDir, proxyUrl, callback);
            } else if ("moss".equals(info.getEngineType())) {
                downloadMossModel(modelDir, callback);
            } else {
                downloadSherpaModel(info, modelDir, callback);
            }
            ModelSettings.saveTtsSettings(modelDir, ModelSettings.loadTtsSettings(modelDir));
            callback.onProgress("完成", 100);
            callback.onComplete();
        } catch (Exception e) {
            callback.onError(e.getMessage() != null ? e.getMessage() : "下载失败");
        }
    }

    private void downloadSherpaModel(TtsModelInfo info, Path modelDir, DownloadProgressCallback callback) throws Exception {
        callback.onProgress("解析 HuggingFace 文件", 5);
        HuggingFaceDownloader downloader = new HuggingFaceDownloader(env);
        downloader.downloadModelFiles(info.id, modelDir, "main", true, 3);
        callback.onProgress("下载完成", 95);
    }

    private void downloadMossModel(Path modelDir, DownloadProgressCallback callback) throws Exception {
        callback.onProgress("下载 MOSS 模型", 5);
        HuggingFaceDownloader downloader = new HuggingFaceDownloader(env);
        downloader.downloadModelFiles("OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX", modelDir, "main", true, 3);
        downloader.downloadModelFiles("OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX", modelDir, "main", true, 3);
        callback.onProgress("下载完成", 95);
    }

    private void downloadArchiveModel(TtsModelInfo info, Path modelDir, String proxyUrl, DownloadProgressCallback callback) throws Exception {
        Files.createDirectories(modelDir.getParent());
        String archiveName = archiveName(info.downloadUrl);
        Path archivePath = modelDir.getParent().resolve(archiveName);
        String finalUrl = buildDownloadUrl(info.downloadUrl, proxyUrl);

        callback.onProgress("下载压缩包", 5);
        downloadFile(finalUrl, archivePath, 5, 60_000, (downloaded, total) -> {
            int percent = total > 0 ? Math.min(85, (int) (downloaded * 80 / total) + 5) : 40;
            callback.onProgress("下载压缩包", percent);
        });

        callback.onProgress("解压模型", 90);
        Path tempDir = modelDir.getParent().resolve(modelDir.getFileName().toString() + "-extract");
        deleteRecursivelyIfExists(tempDir);
        Files.createDirectories(tempDir);
        extractTarBz2(archivePath, tempDir);

        Path extractedModelDir = resolveExtractedModelDir(tempDir);
        deleteRecursivelyIfExists(modelDir);
        Files.move(extractedModelDir, modelDir, StandardCopyOption.REPLACE_EXISTING);
        deleteRecursivelyIfExists(tempDir);
        Files.deleteIfExists(archivePath);
        callback.onProgress("解压完成", 95);
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
