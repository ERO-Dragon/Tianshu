package com.rheinmetal.tianshu.intake.model;

import com.rheinmetal.tianshu.model.ModelDownloadHttpClient;
import com.rheinmetal.tianshu.model.ModelDownloadSourcePolicy;
import com.rheinmetal.tianshu.model.HuggingFaceDownloader;
import com.rheinmetal.tianshu.model.HuggingFaceDownloader.RepositoryFile;
import com.rheinmetal.tianshu.model.HuggingFaceDownloader.RepositoryTree;
import com.rheinmetal.tianshu.model.ModelDownloadProgress;
import com.rheinmetal.tianshu.model.ModelDownloadStage;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.model.lifecycle.ModelDownloadCallback;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class AsrModelDownloader {

    private static final String HF_OFFICIAL = "https://huggingface.co";
    private static final String HF_MIRROR = "https://hf-mirror.com";
    private static final String REVISION = "main";
    private static final int HF_FILE_ATTEMPTS = 3;
    private static final ModelDownloadHttpClient.RetryPolicy ARCHIVE_RETRY =
            new ModelDownloadHttpClient.RetryPolicy(5, 60_000, 60_000, 1_000L);

    private final IGameEnvironment env;
    private final HuggingFaceDownloader repositories;
    private final ModelDownloadHttpClient http;
    private final Supplier<String> preferredHfBaseSupplier;

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
        this(
                env,
                new ModelDownloadSourcePolicy(HF_OFFICIAL, HF_MIRROR),
                new ModelDownloadHttpClient(env),
                HuggingFaceDownloader::getActiveBaseUrl
        );
    }

    AsrModelDownloader(
            IGameEnvironment env,
            ModelDownloadSourcePolicy sources,
            ModelDownloadHttpClient http,
            Supplier<String> preferredHfBaseSupplier
    ) {
        this.env = Objects.requireNonNull(env, "env");
        this.repositories = new HuggingFaceDownloader(env, sources, http, preferredHfBaseSupplier);
        this.http = Objects.requireNonNull(http, "http");
        this.preferredHfBaseSupplier = Objects.requireNonNull(preferredHfBaseSupplier, "preferredHfBaseSupplier");
    }

    public void downloadSync(AsrModelInfo info, Path targetDir, String githubProxyUrl, ModelDownloadCallback callback, DownloadControl control) throws Exception {
        doDownload(info, targetDir, githubProxyUrl, callback, control == null ? () -> {} : control);
    }

    public void cancelActiveTransfers() {
        http.cancelActiveTransfers();
    }

    private void doDownload(AsrModelInfo info, Path targetDir, String githubProxyUrl, ModelDownloadCallback callback, DownloadControl control) throws Exception {
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
            callback.onProgress(ModelDownloadProgress.stage(ModelDownloadStage.COMPLETED, 100, "download.completed"));
            callback.onComplete();
        } catch (Exception e) {
            deleteRecursivelyIfExists(stagingDir);
            DownloadCancelledException cancelled = findDownloadCancelled(e);
            if (cancelled != null) {
                throw cancelled;
            }
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

    private void downloadFromHuggingFace(AsrModelInfo info, List<String> requiredFiles, Path stagingDir, ModelDownloadCallback callback, DownloadControl control) throws Exception {
        callback.onProgress(ModelDownloadProgress.stage(ModelDownloadStage.CHECKING_NETWORK, 0, "network.check"));
        String preferredBase = preferredHfBaseSupplier.get();
        String repoId = info.remoteRepoId();
        if (repoId.isBlank()) {
            throw new IOException("ASR model [" + info.getDisplayName() + "] has no HuggingFace repo id");
        }
        env.info("ASR_HF_RESOLVE repo=" + repoId + " preferred=" + preferredBase);

        callback.onProgress(ModelDownloadProgress.stage(ModelDownloadStage.RESOLVING_FILES, 0, "files.resolve"));
        RepositoryTree tree = repositories.fetchFileTree(preferredBase, repoId, REVISION, control::awaitReady);
        preferredBase = tree.baseUrl();
        Map<String, RepositoryFile> repoFiles = tree.files().stream()
                .collect(Collectors.toMap(RepositoryFile::path, file -> file, (first, duplicate) -> first));
        control.awaitReady();
        List<SourceTarget> downloads = resolveRequestedFiles(
                requiredFiles,
                tree.files().stream().map(RepositoryFile::path).toList()
        );
        env.info("ASR selected files: " + downloads.stream().map(SourceTarget::display).collect(Collectors.joining(", ")));

        int total = downloads.size();
        long totalBytes = knownTotalBytes(downloads, repoFiles);
        if (totalBytes <= 0L) {
            totalBytes = Math.max(0L, info.size);
        }
        final long aggregateTotalBytes = totalBytes;
        long completedBytes = 0L;
        for (int i = 0; i < total; i++) {
            control.awaitReady();
            SourceTarget item = downloads.get(i);
            Path localPath = stagingDir.resolve(item.targetPath()).normalize();
            ensureWithinTarget(localPath, stagingDir);
            long fileStartBytes = completedBytes;
            preferredBase = repositories.downloadRepositoryFile(
                    preferredBase, repoId, REVISION, repoFiles.get(item.sourcePath()), localPath,
                    HF_FILE_ATTEMPTS, control::awaitReady,
                    (downloaded, fileTotal) -> {
                        long effectiveTotal = aggregateTotalBytes > 0L
                                ? aggregateTotalBytes
                                : fileTotal > 0L ? fileStartBytes + fileTotal : 0L;
                        long aggregateDownloaded = fileStartBytes + downloaded;
                        int percent = effectiveTotal > 0L
                                ? (int) Math.min(100L, aggregateDownloaded * 100L / effectiveTotal)
                                : 0;
                        callback.onProgress(ModelDownloadProgress.bytes(
                                ModelDownloadStage.DOWNLOADING,
                                percent,
                                aggregateDownloaded,
                                effectiveTotal,
                                "model.files.download"
                        ));
                    }
            );
            control.awaitReady();
            completedBytes += Files.size(localPath);
        }
    }

    private void downloadFromArchive(AsrModelInfo info, List<String> requiredFiles, Path stagingDir, Path targetDir, String githubProxyUrl, ModelDownloadCallback callback, DownloadControl control) throws Exception {
        callback.onProgress(ModelDownloadProgress.stage(ModelDownloadStage.CHECKING_NETWORK, 0, "network.check"));
        boolean proxyFirst = githubProxyUrl != null && !githubProxyUrl.isBlank();
        List<URI> candidates = ModelDownloadSourcePolicy.githubArchiveCandidates(
                info.downloadUrl,
                githubProxyUrl,
                proxyFirst
        );
        env.info("ASR_ARCHIVE_RESOLVE candidates=" + candidates.size() + " proxyFirst=" + proxyFirst);

        Path archivePath = targetDir.resolveSibling(targetDir.getFileName() + archiveSuffix(info.downloadUrl));
        Path extractDir = targetDir.resolveSibling(targetDir.getFileName() + "-extract");
        Files.deleteIfExists(archivePath);
        deleteRecursivelyIfExists(extractDir);
        try {
            callback.onProgress(ModelDownloadProgress.stage(ModelDownloadStage.DOWNLOADING, 0, "archive.download"));
            http.download(
                    candidates,
                    archivePath,
                    ARCHIVE_RETRY,
                    adapt(control),
                    (downloaded, total) -> {
                        long effectiveTotal = total > 0L ? total : Math.max(0L, info.size);
                        int percent = effectiveTotal > 0L
                                ? (int) Math.min(100L, downloaded * 100L / effectiveTotal)
                                : 0;
                        callback.onProgress(ModelDownloadProgress.bytes(
                                ModelDownloadStage.DOWNLOADING,
                                percent,
                                downloaded,
                                effectiveTotal,
                                "archive.download"
                        ));
                    }
            );
            control.awaitReady();

            callback.onProgress(ModelDownloadProgress.stage(ModelDownloadStage.EXTRACTING, 0, "archive.extract"));
            Files.createDirectories(extractDir);
            extractArchive(archivePath, extractDir, info.downloadUrl);
            control.awaitReady();

            callback.onProgress(ModelDownloadProgress.stage(ModelDownloadStage.MATERIALIZING, 0, "model.materialize"));
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

    private List<String> listRelativeFiles(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .collect(Collectors.toList());
        }
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

    private static ModelDownloadHttpClient.DownloadControl adapt(DownloadControl control) {
        return () -> {
            try {
                control.awaitReady();
            } catch (DownloadCancelledException cancelled) {
                throw new ModelDownloadHttpClient.DownloadCancelledException(
                        "ASR_MODEL_DOWNLOAD_CANCELLED",
                        cancelled
                );
            }
        };
    }

    private static DownloadCancelledException findDownloadCancelled(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof DownloadCancelledException cancelled) {
                return cancelled;
            }
            current = current.getCause();
        }
        return null;
    }

    private record SourceTarget(String sourcePath, String targetPath) {
        private String display() {
            return sourcePath + " -> " + targetPath;
        }
    }

    private long knownTotalBytes(List<SourceTarget> downloads, Map<String, RepositoryFile> repoFiles) {
        long total = 0L;
        for (SourceTarget download : downloads) {
            long size = repoFiles.get(download.sourcePath()).size();
            if (size <= 0L || Long.MAX_VALUE - total < size) {
                return 0L;
            }
            total += size;
        }
        return total;
    }

}
