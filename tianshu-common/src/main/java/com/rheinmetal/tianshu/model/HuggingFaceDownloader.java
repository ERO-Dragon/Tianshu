package com.rheinmetal.tianshu.model;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.IGameEnvironment;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

public class HuggingFaceDownloader {
    private static final String HF_OFFICIAL = "https://huggingface.co";
    private static final String HF_MIRROR = "https://hf-mirror.com";
    private static final String VOCODER_REPO = "csukuangfj/sherpa-onnx-vocoders";
    private static final String MAIN_REVISION = "main";
    private static final Gson GSON = new Gson();
    private static final ModelDownloadHttpClient.RetryPolicy TREE_RETRY =
            new ModelDownloadHttpClient.RetryPolicy(1, 10_000, 15_000, 0L);

    private static volatile String activeBaseUrl;

    private final IGameEnvironment env;
    private final ModelDownloadSourcePolicy sources;
    private final ModelDownloadHttpClient http;
    private final Supplier<String> preferredBaseSupplier;

    @FunctionalInterface
    public interface DownloadControl {
        void checkCancelled() throws IOException;
    }

    public interface DownloadProgressListener {
        default void onFileListResolved(int totalFiles) {
        }

        default void onFileProgress(
                String filePath,
                int fileIndex,
                int totalFiles,
                long downloadedBytes,
                long totalBytes
        ) {
        }
    }

    public HuggingFaceDownloader(IGameEnvironment env) {
        this(
                env,
                new ModelDownloadSourcePolicy(HF_OFFICIAL, HF_MIRROR),
                new ModelDownloadHttpClient(env),
                HuggingFaceDownloader::getActiveBaseUrl
        );
    }

    HuggingFaceDownloader(
            IGameEnvironment env,
            ModelDownloadSourcePolicy sources,
            ModelDownloadHttpClient http,
            Supplier<String> preferredBaseSupplier
    ) {
        this.env = Objects.requireNonNull(env, "env");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.http = Objects.requireNonNull(http, "http");
        this.preferredBaseSupplier = Objects.requireNonNull(preferredBaseSupplier, "preferredBaseSupplier");
    }

    public void cancelActiveTransfers() {
        http.cancelActiveTransfers();
    }

    public static synchronized String getActiveBaseUrl() {
        if (activeBaseUrl != null) {
            return activeBaseUrl;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("huggingface.co", 443), 300);
            activeBaseUrl = HF_OFFICIAL;
        } catch (IOException unreachable) {
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
        Objects.requireNonNull(revision, "revision");
        Files.createDirectories(targetDir);

        String preferredBase = preferredBaseSupplier.get();
        env.info("HF_REPOSITORY_RESOLVE repo=" + repoId + " preferred=" + preferredBase);
        checkControl(control);
        List<String> allFiles = fetchFileTree(preferredBase, repoId, revision, control);
        List<String> toDownload = allFiles.stream().filter(path -> !shouldSkipFile(path)).toList();
        if (progress != null) {
            progress.onFileListResolved(toDownload.size());
        }

        for (int index = 0; index < toDownload.size(); index++) {
            checkControl(control);
            String filePath = toDownload.get(index);
            int fileIndex = index + 1;
            Path localPath = targetDir.resolve(filePath).normalize();
            ensureWithinTarget(localPath, targetDir);
            if (skipExisting && Files.isRegularFile(localPath) && Files.size(localPath) > 0L) {
                notifySkipped(progress, filePath, fileIndex, toDownload.size());
                continue;
            }
            downloadRepositoryFile(
                    preferredBase,
                    repoId,
                    revision,
                    filePath,
                    localPath,
                    maxRetries,
                    control,
                    progress,
                    fileIndex,
                    toDownload.size()
            );
        }
    }

    public void downloadVocoder(Path vocoderDir, int maxRetries) throws Exception {
        downloadVocoder(vocoderDir, maxRetries, null, null);
    }

    public void downloadVocoder(
            Path vocoderDir,
            int maxRetries,
            DownloadControl control,
            DownloadProgressListener progress
    ) throws Exception {
        Objects.requireNonNull(vocoderDir, "vocoderDir");
        Files.createDirectories(vocoderDir);
        String preferredBase = preferredBaseSupplier.get();
        checkControl(control);
        List<String> toDownload = fetchFileTree(preferredBase, VOCODER_REPO, MAIN_REVISION, control)
                .stream()
                .filter(filePath -> filePath.toLowerCase(Locale.ROOT).endsWith(".onnx"))
                .filter(filePath -> !shouldSkipFile(filePath))
                .toList();
        if (progress != null) {
            progress.onFileListResolved(toDownload.size());
        }
        for (int index = 0; index < toDownload.size(); index++) {
            checkControl(control);
            String filePath = toDownload.get(index);
            int fileIndex = index + 1;
            Path localPath = vocoderDir.resolve(filePath).normalize();
            ensureWithinTarget(localPath, vocoderDir);
            if (Files.isRegularFile(localPath) && Files.size(localPath) > 0L) {
                notifySkipped(progress, filePath, fileIndex, toDownload.size());
                continue;
            }
            downloadRepositoryFile(
                    preferredBase,
                    VOCODER_REPO,
                    MAIN_REVISION,
                    filePath,
                    localPath,
                    maxRetries,
                    control,
                    progress,
                    fileIndex,
                    toDownload.size()
            );
        }
    }

    public void downloadSingleFile(
            String repoId,
            String filePath,
            Path targetFile,
            String revision,
            int maxRetries
    ) throws Exception {
        downloadSingleFile(repoId, filePath, targetFile, revision, maxRetries, null, null);
    }

    public void downloadSingleFile(
            String repoId,
            String filePath,
            Path targetFile,
            String revision,
            int maxRetries,
            DownloadControl control,
            DownloadProgressListener progress
    ) throws Exception {
        Objects.requireNonNull(repoId, "repoId");
        Objects.requireNonNull(filePath, "filePath");
        Objects.requireNonNull(targetFile, "targetFile");
        Objects.requireNonNull(revision, "revision");
        Path absoluteTarget = targetFile.toAbsolutePath().normalize();
        Path parent = absoluteTarget.getParent();
        if (parent == null) {
            throw new IOException("HF_TARGET_HAS_NO_PARENT target=" + targetFile);
        }
        Files.createDirectories(parent);
        ensureWithinTarget(absoluteTarget, parent);
        if (Files.isRegularFile(absoluteTarget) && Files.size(absoluteTarget) > 0L) {
            notifySkipped(progress, filePath, 1, 1);
            return;
        }
        if (progress != null) {
            progress.onFileListResolved(1);
        }
        String preferredBase = preferredBaseSupplier.get();
        downloadRepositoryFile(
                preferredBase,
                repoId,
                revision,
                filePath,
                absoluteTarget,
                maxRetries,
                control,
                progress,
                1,
                1
        );
    }

    private List<String> fetchFileTree(
            String preferredBase,
            String repoId,
            String revision,
            DownloadControl control
    ) throws IOException {
        String json = http.readUtf8(
                sources.huggingFaceTreeCandidates(preferredBase, repoId, revision),
                TREE_RETRY,
                adapt(control)
        );
        JsonArray root = GSON.fromJson(json, JsonArray.class);
        if (root == null) {
            throw new IOException("HF_TREE_RESPONSE_INVALID repo=" + repoId);
        }
        List<String> result = new ArrayList<>();
        for (JsonElement element : root) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject node = element.getAsJsonObject();
            if (!node.has("type") || !node.has("path")) {
                continue;
            }
            if ("file".equalsIgnoreCase(node.get("type").getAsString())) {
                result.add(node.get("path").getAsString());
            }
        }
        return List.copyOf(result);
    }

    private void downloadRepositoryFile(
            String preferredBase,
            String repoId,
            String revision,
            String filePath,
            Path target,
            int maxRetries,
            DownloadControl control,
            DownloadProgressListener progress,
            int fileIndex,
            int totalFiles
    ) throws IOException {
        http.download(
                sources.huggingFaceFileCandidates(preferredBase, repoId, revision, filePath),
                target,
                fileRetry(maxRetries),
                adapt(control),
                (downloaded, total) -> {
                    if (progress != null) {
                        progress.onFileProgress(filePath, fileIndex, totalFiles, downloaded, total);
                    }
                }
        );
        env.info("HF_FILE_COMPLETE repo=" + repoId + " file=" + filePath);
    }

    private static ModelDownloadHttpClient.RetryPolicy fileRetry(int maxRetries) {
        return new ModelDownloadHttpClient.RetryPolicy(maxRetries, 15_000, 120_000, 1_000L);
    }

    private static ModelDownloadHttpClient.DownloadControl adapt(DownloadControl control) {
        return control == null ? () -> {} : control::checkCancelled;
    }

    private static void checkControl(DownloadControl control) throws IOException {
        if (control != null) {
            control.checkCancelled();
        }
    }

    private static void notifySkipped(
            DownloadProgressListener progress,
            String filePath,
            int fileIndex,
            int totalFiles
    ) {
        if (progress != null) {
            if (totalFiles == 1) {
                progress.onFileListResolved(1);
            }
            progress.onFileProgress(filePath, fileIndex, totalFiles, 1L, 1L);
        }
    }

    private static boolean shouldSkipFile(String path) {
        String lowerPath = path.toLowerCase(Locale.ROOT);
        if (lowerPath.startsWith("dict/") || lowerPath.startsWith("dict\\")) {
            return true;
        }
        if (lowerPath.endsWith(".py")) {
            return true;
        }
        int lastSlash = lowerPath.lastIndexOf('/');
        String fileName = lastSlash >= 0 ? lowerPath.substring(lastSlash + 1) : lowerPath;
        return fileName.equals(".gitattributes")
                || fileName.equals("readme")
                || fileName.startsWith("readme.");
    }

    private static void ensureWithinTarget(Path path, Path targetDir) throws IOException {
        Path normalizedPath = path.toAbsolutePath().normalize();
        Path normalizedTarget = targetDir.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(normalizedTarget)) {
            throw new IOException("HF_UNSAFE_TARGET_PATH path=" + path);
        }
    }
}
