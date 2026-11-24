package com.rheinmetal.tianshu.model;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.IGameEnvironment;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLException;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

public class HuggingFaceDownloader {
    private static final String HF_OFFICIAL = "https://huggingface.co";
    private static final String HF_MIRROR = "https://hf-mirror.com";
    private static final String VOCODER_REPO = "csukuangfj/sherpa-onnx-vocoders";
    private static final String MAIN_REVISION = "main";
    private static final int FILE_CONNECT_TIMEOUT_MILLIS = 15_000;
    private static final int FILE_READ_TIMEOUT_MILLIS = 120_000;
    private static final long FILE_RETRY_BACKOFF_MILLIS = 1_000L;
    private static final Gson GSON = new Gson();
        /** A repository listing is paginated; this bounds the walk so a looping server cannot hang a download. */
    private static final int MAX_TREE_PAGES = 50;
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

        default void onOverallProgress(long downloadedBytes, long totalBytes) {
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

    public HuggingFaceDownloader(
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

    /**
     * Returns the selected host without making a network request.
     *
     * <p>The first request goes to the official source. A failed transfer switches to the mirror. Keeping host
     * selection free of network I/O ensures every request passes through the platform access policy.
     */
    public static synchronized String getActiveBaseUrl() {
        if (activeBaseUrl != null) {
            return activeBaseUrl;
        }
        activeBaseUrl = HF_OFFICIAL;
        return activeBaseUrl;
    }

    public static synchronized void resetDomainCache() {
        activeBaseUrl = null;
    }

    /**
     * The host to try after a connectivity failure: the other one, and it becomes the active host.
     *
     * <p>A probe can only be wrong in the direction of "looks reachable"; a download that dies in transit is proof it
     * is not, so the sibling host gets one immediate attempt. The one that is about to serve data is then adopted as
     * the active host: only clearing the cache made the next file probe again, and on a network where the API answers
     * but the file CDN is filtered, that probe keeps picking the official host — every file then burns its whole retry
     * budget before falling back to the mirror that works. One model is a dozen files, so that is the difference
     * between minutes and an hour.</p>
     */
    private String fallbackBaseUrl(String failedBaseUrl) {
        String fallback = sources.officialBase().equals(failedBaseUrl) ? sources.mirrorBase() : sources.officialBase();
        adoptBaseUrl(fallback);
        return fallback;
    }

    /**
     * Adopts the host that actually answered.
     *
     * <p>The candidate list offered to one request contains both hosts, so a success deep inside the transport never
     * passed through {@link #fallbackBaseUrl} — the session kept believing it was on the official host and every later
     * file waited out the same failure again. This is the missing half: success reports its source, and the source wins.</p>
     */
    private void adoptAnsweringSource(URI source) {
        if (source == null) {
            return;
        }
        String base = baseOf(source);
        if (!base.equals(activeBaseUrl)) {
            adoptBaseUrl(base);
            env.info("HF_DOWNLOAD_SOURCE_ADOPTED base=" + base);
        }
    }

    /** {@code scheme://authority} — the host part a source and its base share, with no path. */
    private static String baseOf(URI source) {
        String authority = source.getAuthority();
        return authority == null ? source.toString() : source.getScheme() + "://" + authority;
    }

    /** Records the host the session is actually using, so later files skip both the probe and the failure. */
    private static synchronized void adoptBaseUrl(String baseUrl) {
        activeBaseUrl = baseUrl;
    }

    /** Connectivity-class failures are the ones a different host may fix; HTTP and disk errors are not. */
    private static boolean isConnectivityFailure(Throwable failure) {
        return looksLikeConnectivityFailure(failure, 0);
    }

    /**
     * Walks the whole failure tree, suppressed failures included: the transport aggregates one failure per
     * source, so the reason it never reaches the surface as a plain cause chain.
     */
    private static boolean looksLikeConnectivityFailure(Throwable failure, int depth) {
        if (failure == null || depth > 8) {
            return false;
        }
        if (failure instanceof SocketTimeoutException
                || failure instanceof ConnectException
                || failure instanceof UnknownHostException
                || failure instanceof NoRouteToHostException
                || failure instanceof SSLException) {
            return true;
        }
        if (looksLikeConnectivityFailure(failure.getCause(), depth + 1)) {
            return true;
        }
        for (Throwable suppressed : failure.getSuppressed()) {
            if (looksLikeConnectivityFailure(suppressed, depth + 1)) {
                return true;
            }
        }
        return false;
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
        RepositoryTree tree = fetchFileTree(preferredBase, repoId, revision, control);
        preferredBase = tree.baseUrl();
        List<RepositoryFile> toDownload = tree.files().stream().filter(file -> !shouldSkipFile(file.path())).toList();
        long totalBytes = totalBytes(toDownload);
        long completedBytes = 0L;
        if (progress != null) {
            progress.onFileListResolved(toDownload.size());
            progress.onOverallProgress(completedBytes, totalBytes);
        }

        for (int index = 0; index < toDownload.size(); index++) {
            checkControl(control);
            RepositoryFile repositoryFile = toDownload.get(index);
            String filePath = repositoryFile.path();
            int fileIndex = index + 1;
            Path localPath = targetDir.resolve(filePath).normalize();
            ensureWithinTarget(localPath, targetDir);
            if (skipExisting && existingFileIsUsable(localPath, repositoryFile)) {
                notifySkipped(progress, filePath, fileIndex, toDownload.size(), completedBytes, totalBytes, repositoryFile.size());
                completedBytes += knownSize(repositoryFile.size(), Files.size(localPath));
                continue;
            }
            long fileStartBytes = completedBytes;
            preferredBase = downloadRepositoryFile(
                    preferredBase,
                    repoId,
                    revision,
                    repositoryFile,
                    localPath,
                    maxRetries,
                    control,
                    progress,
                    fileIndex,
                    toDownload.size(),
                    fileStartBytes,
                    totalBytes
            );
            completedBytes += knownSize(repositoryFile.size(), Files.size(localPath));
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
        RepositoryTree tree = fetchFileTree(preferredBase, VOCODER_REPO, MAIN_REVISION, control);
        preferredBase = tree.baseUrl();
        List<RepositoryFile> toDownload = tree.files()
                .stream()
                .filter(file -> file.path().toLowerCase(Locale.ROOT).endsWith(".onnx"))
                .filter(file -> !shouldSkipFile(file.path()))
                .toList();
        long totalBytes = totalBytes(toDownload);
        long completedBytes = 0L;
        if (progress != null) {
            progress.onFileListResolved(toDownload.size());
            progress.onOverallProgress(completedBytes, totalBytes);
        }
        for (int index = 0; index < toDownload.size(); index++) {
            checkControl(control);
            RepositoryFile repositoryFile = toDownload.get(index);
            String filePath = repositoryFile.path();
            int fileIndex = index + 1;
            Path localPath = vocoderDir.resolve(filePath).normalize();
            ensureWithinTarget(localPath, vocoderDir);
            if (existingFileIsUsable(localPath, repositoryFile)) {
                notifySkipped(progress, filePath, fileIndex, toDownload.size(), completedBytes, totalBytes, repositoryFile.size());
                completedBytes += knownSize(repositoryFile.size(), Files.size(localPath));
                continue;
            }
            long fileStartBytes = completedBytes;
            preferredBase = downloadRepositoryFile(
                    preferredBase,
                    VOCODER_REPO,
                    MAIN_REVISION,
                    repositoryFile,
                    localPath,
                    maxRetries,
                    control,
                    progress,
                    fileIndex,
                    toDownload.size(),
                    fileStartBytes,
                    totalBytes
            );
            completedBytes += knownSize(repositoryFile.size(), Files.size(localPath));
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
            notifySkipped(progress, filePath, 1, 1, 0L, 0L, Files.size(absoluteTarget));
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
                // A single file is not resolved through a tree, so there is no declared size or digest to
                // check against; the transport's own length check is all this call can rely on.
                new RepositoryFile(filePath, -1L, null),
                absoluteTarget,
                maxRetries,
                control,
                progress,
                1,
                1,
                0L,
                0L
        );
    }

    /** Lists all files and identifies the responding source for subsequent file transfers. */
    public RepositoryTree fetchFileTree(
            String preferredBase,
            String repoId,
            String revision,
            DownloadControl control
    ) throws IOException {
        ModelDownloadHttpClient.Page page;
        try {
            page = http.readUtf8Page(
                    sources.huggingFaceTreeCandidates(preferredBase, repoId, revision),
                    TREE_RETRY,
                    adapt(control)
            );
        } catch (IOException failure) {
            if (!isConnectivityFailure(failure)) {
                throw failure;
            }
            String fallback = fallbackBaseUrl(preferredBase);
            env.warn("[model.download] tree fetch died in transit on " + preferredBase
                    + "; retrying via " + fallback);
            page = http.readUtf8Page(
                    sources.huggingFaceTreeCandidates(fallback, repoId, revision),
                    TREE_RETRY,
                    adapt(control)
            );
        }
        // The first page may have come from the mirror inside a single request; adopt it before any file is fetched.
        adoptAnsweringSource(page.source());
        List<RepositoryFile> result = new ArrayList<>();
        // Follow every page: a repository whose weights land past the first page used to download as configuration
        // only, and the caller was told the model was complete.
        int pages = 0;
        while (true) {
            if (++pages > MAX_TREE_PAGES) {
                throw new IOException("HF_TREE_TOO_MANY_PAGES repo=" + repoId + " pages=" + pages);
            }
            appendTreePage(page.body(), repoId, result);
            if (page.next() == null) {
                return new RepositoryTree(result, baseOf(page.source()));
            }
            page = http.readUtf8Page(List.of(page.next()), TREE_RETRY, adapt(control));
            adoptAnsweringSource(page.source());
        }
    }

    /** Reads one tree response into {@code result}; throws when the body is not a JSON array at all. */
    private static void appendTreePage(String json, String repoId, List<RepositoryFile> result) throws IOException {
        JsonArray root;
        try {
            root = GSON.fromJson(json, JsonArray.class);
        } catch (RuntimeException malformed) {
            // A captive portal or a CDN error page answers 200 with HTML. Gson throws unchecked, which used to skip
            // both the mirror fallback and the retry policy; a source failure has to look like one.
            throw new IOException("HF_TREE_RESPONSE_INVALID repo=" + repoId, malformed);
        }
        if (root == null) {
            throw new IOException("HF_TREE_RESPONSE_INVALID repo=" + repoId);
        }
        for (JsonElement element : root) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject node = element.getAsJsonObject();
            if (!node.has("type") || !node.has("path")) {
                continue;
            }
            if ("file".equalsIgnoreCase(node.get("type").getAsString())) {
                result.add(new RepositoryFile(
                        node.get("path").getAsString(),
                        readSize(node),
                        readChecksum(node)
                ));
            }
        }
    }

    /**
     * The sha256 the repository publishes for a large file, or {@code null} when it publishes none.
     *
     * <p>Hugging Face reports LFS content as {@code lfs: {oid, size}} where {@code oid} is the sha256 of the
     * bytes. Small non-LFS files have no digest, and a download must not fail just because a repository
     * chose not to publish one.</p>
     */
    private static String readChecksum(JsonObject node) {
        if (!node.has("lfs") || !node.get("lfs").isJsonObject()) {
            return null;
        }
        JsonObject lfs = node.getAsJsonObject("lfs");
        if (!lfs.has("oid") || lfs.get("oid").isJsonNull()) {
            return null;
        }
        String oid = lfs.get("oid").getAsString();
        return oid == null || oid.isBlank() ? null : oid.trim();
    }

    private String downloadRepositoryFile(
            String preferredBase,
            String repoId,
            String revision,
            RepositoryFile file,
            Path target,
            int maxRetries,
            DownloadControl control,
            DownloadProgressListener progress,
            int fileIndex,
            int totalFiles,
            long fileStartBytes,
            long overallTotalBytes
    ) throws IOException {
        return downloadRepositoryFile(preferredBase, repoId, revision, file, target, maxRetries, control,
                (downloaded, total) -> {
                    if (progress != null) {
                        progress.onFileProgress(file.path(), fileIndex, totalFiles, downloaded, total);
                        progress.onOverallProgress(fileStartBytes + downloaded, Math.max(0L, overallTotalBytes));
                    }
                });
    }

    /** Downloads a selected repository file to the caller's path and returns the source for the next file. */
    public String downloadRepositoryFile(String preferredBase, String repoId, String revision, RepositoryFile file,
                                         Path target, int maxRetries, DownloadControl control,
                                         ModelDownloadHttpClient.ProgressListener progress) throws IOException {
        String filePath = file.path();
        List<URI> candidates = sources.huggingFaceFileCandidates(preferredBase, repoId, revision, filePath);
        URI answeringSource;
        try {
            answeringSource = downloadCandidates(candidates, target, file, maxRetries, control, progress);
        } catch (IOException failure) {
            IOException rejected = verificationFailureIn(failure);
            if (rejected != null) {
                // The bytes were wrong, not the route to them: the retries already happened inside the transfer loop,
                // so report the published fact instead of "all sources failed", which sent the user to their network.
                throw rejected;
            }
            if (!isConnectivityFailure(failure)) {
                throw failure;
            }
            String fallback = fallbackBaseUrl(preferredBase);
            env.warn("[model.download] file download died in transit on " + preferredBase
                    + "; retrying via " + fallback);
            answeringSource = downloadCandidates(
                    sources.huggingFaceFileCandidates(fallback, repoId, revision, filePath),
                    target, file, maxRetries, control, progress);
        }
        adoptAnsweringSource(answeringSource);
        env.info("HF_FILE_COMPLETE repo=" + repoId + " file=" + filePath);
        return baseOf(answeringSource);
    }

    /** The @return the verification failure anywhere in the aggregated cause tree, or null when there was none. */
    private static IOException verificationFailureIn(Throwable failure) {
        return scanForVerificationFailure(failure, 0);
    }

    private static IOException scanForVerificationFailure(Throwable failure, int depth) {
        if (failure == null || depth > 8) {
            return null;
        }
        if (failure instanceof VerificationFailure rejected) {
            return rejected;
        }
        IOException fromCause = scanForVerificationFailure(failure.getCause(), depth + 1);
        if (fromCause != null) {
            return fromCause;
        }
        for (Throwable suppressed : failure.getSuppressed()) {
            IOException fromSuppressed = scanForVerificationFailure(suppressed, depth + 1);
            if (fromSuppressed != null) {
                return fromSuppressed;
            }
        }
        return null;
    }

    /**
     * A transfer whose bytes do not match what the repository published.
     *
     * <p>Its own type so the caller can tell it apart from a transport failure after the retries: a bad body is a
     * reason to re-download from the source that served it, not to switch hosts.</p>
     */
    static final class VerificationFailure extends IOException {
        private static final long serialVersionUID = 1L;

        VerificationFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Checks the bytes that landed on disk against what the repository declared.
     *
     * <p>The transport can only compare against the response's own {@code Content-Length}, which says how
     * much the server sent, not what it sent. A resumed transfer that started from a file whose tail never
     * reached the disk, or a proxy that served a truncated body with a matching length, would pass that
     * check and produce a model that fails much later inside the runtime. The declared size and the digest
     * are the two facts that come from the repository rather than from the transfer.</p>
     *
     * <p>A file that fails is deleted, so the retry re-downloads it instead of skipping it as "already
     * present".</p>
     */
    private void verifyDownloadedFile(Path target, RepositoryFile file) throws IOException {
        long actualSize = Files.size(target);
        if (file.size() > 0L && actualSize != file.size()) {
            Files.deleteIfExists(target);
            throw new IOException("MODEL_FILE_SIZE_MISMATCH file=" + file.path()
                    + " expected=" + file.size() + " actual=" + actualSize);
        }
        String expected = file.sha256();
        if (expected == null || expected.isBlank()) {
            return;
        }
        String actual = sha256(target);
        if (!expected.equalsIgnoreCase(actual)) {
            Files.deleteIfExists(target);
            throw new IOException("MODEL_FILE_CHECKSUM_MISMATCH file=" + file.path()
                    + " expected=" + expected + " actual=" + actual);
        }
    }

    /**
     * Whether a file already on disk may stand in for this repository file.
     *
     * <p>The check used to be "is a non-empty file", so anything left by an older layout, a hand copy or a killed
     * process was never examined again — and a file whose size happened to match but whose content did not survived
     * to crash the native loader. Now the published size and checksum are compared, and a bad copy is removed so the
     * download below cannot skip it as "already present".</p>
     *
     * @return true when the existing file is the published one; false when it must be re-downloaded
     */
    private boolean existingFileIsUsable(Path local, RepositoryFile file) throws IOException {
        if (!Files.isRegularFile(local)) {
            return false;
        }
        long actualSize = Files.size(local);
        if (actualSize <= 0L) {
            Files.deleteIfExists(local);
            return false;
        }
        if (file.size() > 0L && actualSize != file.size()) {
            Files.deleteIfExists(local);
            return false;
        }
        String expected = file.sha256();
        if (expected == null || expected.isBlank()) {
            // Nothing published to compare against: size is all the repository gave us.
            return true;
        }
        if (expected.equalsIgnoreCase(sha256(local))) {
            return true;
        }
        Files.deleteIfExists(local);
        return false;
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException missing) {
            throw new IOException("SHA-256 is not available on this runtime", missing);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) {
            hex.append(Character.forDigit((value >> 4) & 0xF, 16));
            hex.append(Character.forDigit(value & 0xF, 16));
        }
        return hex.toString();
    }

    /** Downloads from whichever candidate answers, and returns the source that did. */
    private URI downloadCandidates(
            List<URI> candidates,
            Path target,
            RepositoryFile file,
            int maxRetries,
            DownloadControl control,
            ModelDownloadHttpClient.ProgressListener progress
    ) throws IOException {
        return http.download(
                candidates,
                target,
                fileRetry(maxRetries),
                adapt(control),
                progress,
                // Verified while it is still the ".downloading" file: the promotion only happens for bytes that match
                // the published size and digest, and a rejected body is deleted so the retry starts from zero.
                temporary -> {
                    try {
                        verifyDownloadedFile(temporary, file);
                    } catch (IOException rejected) {
                        throw new VerificationFailure(rejected.getMessage(), rejected);
                    }
                }
        );
    }

    private static ModelDownloadHttpClient.RetryPolicy fileRetry(int maxRetries) {
        return new ModelDownloadHttpClient.RetryPolicy(maxRetries, FILE_CONNECT_TIMEOUT_MILLIS, FILE_READ_TIMEOUT_MILLIS, FILE_RETRY_BACKOFF_MILLIS);
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
            int totalFiles,
            long completedBytes,
            long overallTotalBytes,
            long knownFileSize
    ) {
        if (progress != null) {
            if (totalFiles == 1) {
                progress.onFileListResolved(1);
            }
            progress.onFileProgress(filePath, fileIndex, totalFiles, knownFileSize > 0L ? knownFileSize : 1L, knownFileSize);
            progress.onOverallProgress(completedBytes + (knownFileSize > 0L ? knownFileSize : 1L), overallTotalBytes);
        }
    }

    private static long totalBytes(List<RepositoryFile> files) {
        long total = 0L;
        for (RepositoryFile file : files) {
            if (file.size() <= 0L || Long.MAX_VALUE - total < file.size()) {
                return 0L;
            }
            total += file.size();
        }
        return total;
    }

    private static long knownSize(long metadataSize, long actualSize) {
        return actualSize > 0L ? actualSize : Math.max(0L, metadataSize);
    }

    private static long readSize(JsonObject node) {
        if (node == null || !node.has("size") || !node.get("size").isJsonPrimitive()) {
            return -1L;
        }
        try {
            long size = node.get("size").getAsLong();
            return size > 0L ? size : -1L;
        } catch (RuntimeException invalidSize) {
            return -1L;
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

    public record RepositoryTree(List<RepositoryFile> files, String baseUrl) {
        public RepositoryTree {
            files = List.copyOf(files);
        }
    }

    public record RepositoryFile(String path, long size, String sha256) {
    }
}
