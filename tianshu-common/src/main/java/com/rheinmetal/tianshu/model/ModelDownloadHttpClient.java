package com.rheinmetal.tianshu.model;

import com.rheinmetal.tianshu.api.IGameEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;

/** Executes model-download HTTP requests while keeping source selection outside transport. */
final class ModelDownloadHttpClient {
    private static final int BUFFER_SIZE = 8192;
    private static final long CONTROL_POLL_MILLIS = 100L;
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNSATISFIED_CONTENT_RANGE = Pattern.compile("bytes\\s+\\*/(\\d+)", Pattern.CASE_INSENSITIVE);

    private final IGameEnvironment env;
    private final Set<HttpURLConnection> activeConnections = ConcurrentHashMap.newKeySet();

    ModelDownloadHttpClient(IGameEnvironment env) {
        this.env = Objects.requireNonNull(env, "env");
    }

    String readUtf8(List<URI> candidates, RetryPolicy policy, DownloadControl control) throws IOException {
        return executeCandidates(candidates, policy, control, this::readUtf8Once);
    }

    void download(
            List<URI> candidates,
            Path target,
            RetryPolicy policy,
            DownloadControl control,
            ProgressListener progress
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        Path absoluteTarget = target.toAbsolutePath().normalize();
        Path parent = absoluteTarget.getParent();
        if (parent == null) {
            throw new IOException("MODEL_DOWNLOAD_TARGET_HAS_NO_PARENT target=" + target);
        }
        Files.createDirectories(parent);
        Path temporary = absoluteTarget.resolveSibling(absoluteTarget.getFileName() + ".downloading");
        Path metadataFile = absoluteTarget.resolveSibling(absoluteTarget.getFileName() + ".downloading.meta");
        try {
            executeCandidates(candidates, policy, control, (candidate, retryPolicy) -> {
                downloadOnce(candidate, temporary, metadataFile, retryPolicy, control, progress);
                moveIntoPlace(temporary, absoluteTarget);
                Files.deleteIfExists(metadataFile);
                return Boolean.TRUE;
            });
        } catch (IOException failure) {
            cleanupUntrustedPartial(temporary, metadataFile, validateCandidates(candidates));
            throw failure;
        }
    }

    void cancelActiveTransfers() {
        for (HttpURLConnection connection : activeConnections) {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String readUtf8Once(URI candidate, RetryPolicy policy) throws IOException {
        HttpURLConnection connection = open(candidate, policy);
        try {
            requireSuccessful(connection, candidate);
            long expectedLength = connection.getContentLengthLong();
            byte[] bytes;
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                bytes = output.toByteArray();
            }
            validateLength(candidate, expectedLength, bytes.length, false);
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            close(connection);
        }
    }

    private void downloadOnce(
            URI candidate,
            Path temporary,
            Path metadataFile,
            RetryPolicy policy,
            DownloadControl control,
            ProgressListener progress
    ) throws IOException {
        ResumeState resume = loadResumeState(candidate, temporary, metadataFile).orElse(null);
        HttpURLConnection connection = open(candidate, policy, resume);
        try {
            int status = connection.getResponseCode();
            if (resume != null && status == 416 && completePartialConfirmed(connection, resume)) {
                return;
            }
            if (resume != null && status == 416) {
                restartFull(candidate, temporary, metadataFile, policy, control, progress, connection, "range_not_satisfiable");
                return;
            }
            if (resume != null && status == HttpURLConnection.HTTP_PARTIAL) {
                try {
                    appendPartial(candidate, temporary, connection, resume, control, progress);
                } catch (ResumeRejectedException rejected) {
                    restartFull(candidate, temporary, metadataFile, policy, control, progress, connection, rejected.getMessage());
                }
                return;
            }
            if (resume != null && status == HttpURLConnection.HTTP_OK) {
                env.info("MODEL_DOWNLOAD_RESUME_RESTART source=" + candidate + " reason=range_not_honored");
                downloadFull(candidate, temporary, metadataFile, connection, control, progress);
                return;
            }
            requireSuccessfulStatus(status, candidate);
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("MODEL_DOWNLOAD_UNEXPECTED_PARTIAL_RESPONSE source=" + candidate + " status=" + status);
            }
            downloadFull(candidate, temporary, metadataFile, connection, control, progress);
        } finally {
            close(connection);
        }
    }

    private void restartFull(
            URI candidate,
            Path temporary,
            Path metadataFile,
            RetryPolicy policy,
            DownloadControl control,
            ProgressListener progress,
            HttpURLConnection previousConnection,
            String reason
    ) throws IOException {
        env.info("MODEL_DOWNLOAD_RESUME_RESTART source=" + candidate + " reason=" + reason);
        Files.deleteIfExists(metadataFile);
        Files.deleteIfExists(temporary);
        close(previousConnection);
        HttpURLConnection fresh = open(candidate, policy);
        try {
            requireSuccessful(fresh, candidate);
            if (fresh.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("MODEL_DOWNLOAD_EXPECTED_FULL_RESPONSE source=" + candidate
                        + " status=" + fresh.getResponseCode());
            }
            downloadFull(candidate, temporary, metadataFile, fresh, control, progress);
        } finally {
            close(fresh);
        }
    }

    private void downloadFull(
            URI candidate,
            Path temporary,
            Path metadataFile,
            HttpURLConnection connection,
            DownloadControl control,
            ProgressListener progress
    ) throws IOException {
        long expectedLength = connection.getContentLengthLong();
        Optional<ModelDownloadResumeMetadata> metadata = ModelDownloadResumeMetadata.fromResponse(candidate, connection);
        Files.deleteIfExists(temporary);
        Files.deleteIfExists(metadataFile);
        if (metadata.isPresent()) {
            metadata.get().write(metadataFile);
        }
        long downloaded = 0L;
        try (InputStream input = connection.getInputStream();
             OutputStream output = Files.newOutputStream(
                     temporary,
                     StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING,
                     StandardOpenOption.WRITE
             )) {
            downloaded = copy(input, output, 0L, expectedLength, control, progress);
        } catch (IOException failure) {
            cleanupIfNotResumable(temporary, metadataFile);
            throw failure;
        }
        try {
            validateLength(candidate, expectedLength, downloaded, true);
        } catch (IOException failure) {
            cleanupIfNotResumable(temporary, metadataFile);
            throw failure;
        }
    }

    private void appendPartial(
            URI candidate,
            Path temporary,
            HttpURLConnection connection,
            ResumeState resume,
            DownloadControl control,
            ProgressListener progress
    ) throws IOException {
        ContentRange range = parseContentRange(connection.getHeaderField("Content-Range"));
        if (range == null
                || range.start() != resume.localLength()
                || range.total() != resume.metadata().totalLength()
                || !resume.metadata().matchesResponse(connection)) {
            throw new ResumeRejectedException("response_mismatch");
        }
        long expectedRemaining = resume.metadata().totalLength() - resume.localLength();
        if (connection.getContentLengthLong() >= 0L && connection.getContentLengthLong() != expectedRemaining) {
            throw new ResumeRejectedException("length_mismatch");
        }
        long downloaded;
        try (InputStream input = connection.getInputStream();
             OutputStream output = Files.newOutputStream(
                     temporary,
                     StandardOpenOption.CREATE,
                     StandardOpenOption.APPEND,
                     StandardOpenOption.WRITE
             )) {
            downloaded = copy(
                    input,
                    output,
                    resume.localLength(),
                    resume.metadata().totalLength(),
                    control,
                    progress
            );
        }
        validateLength(candidate, resume.metadata().totalLength(), downloaded, true);
    }

    private long copy(
            InputStream input,
            OutputStream output,
            long initialLength,
            long totalLength,
            DownloadControl control,
            ProgressListener progress
    ) throws IOException {
        long downloaded = initialLength;
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) != -1) {
            checkControl(control);
            output.write(buffer, 0, read);
            downloaded += read;
            if (progress != null) {
                progress.onProgress(downloaded, totalLength);
            }
        }
        return downloaded;
    }

    private <T> T executeCandidates(
            List<URI> candidates,
            RetryPolicy policy,
            DownloadControl control,
            CandidateOperation<T> operation
    ) throws IOException {
        List<URI> sources = validateCandidates(candidates);
        RetryPolicy retryPolicy = Objects.requireNonNull(policy, "policy");
        List<IOException> sourceFailures = new ArrayList<>();
        for (URI candidate : sources) {
            IOException sourceFailure = null;
            for (int attempt = 1; attempt <= retryPolicy.maxAttempts(); attempt++) {
                checkControl(control);
                try {
                    return operation.run(candidate, retryPolicy);
                } catch (DownloadCancelledException cancelled) {
                    throw cancelled;
                } catch (IOException failure) {
                    checkControl(control);
                    IOException attemptFailure = new IOException(
                            "MODEL_DOWNLOAD_SOURCE_ATTEMPT_FAILED source=" + candidate
                                    + " attempt=" + attempt,
                            failure
                    );
                    if (sourceFailure == null) {
                        sourceFailure = new IOException(
                                "MODEL_DOWNLOAD_SOURCE_FAILED source=" + candidate,
                                attemptFailure
                        );
                    } else {
                        sourceFailure.addSuppressed(attemptFailure);
                    }
                    env.warn("MODEL_DOWNLOAD_RETRY source=" + candidate
                            + " attempt=" + attempt + "/" + retryPolicy.maxAttempts()
                            + " cause=" + failure.getMessage());
                    if (attempt < retryPolicy.maxAttempts()) {
                        awaitBackoff(retryPolicy.backoffMillis(), control);
                    }
                }
            }
            if (sourceFailure != null) {
                sourceFailures.add(sourceFailure);
            }
        }

        IOException aggregate = new IOException("MODEL_DOWNLOAD_ALL_SOURCES_FAILED sources=" + sources.size());
        for (IOException failure : sourceFailures) {
            aggregate.addSuppressed(failure);
        }
        throw aggregate;
    }

    private HttpURLConnection open(URI candidate, RetryPolicy policy) throws IOException {
        return open(candidate, policy, null);
    }

    private HttpURLConnection open(URI candidate, RetryPolicy policy, ResumeState resume) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) candidate.toURL().openConnection();
        activeConnections.add(connection);
        try {
            connection.setConnectTimeout(policy.connectTimeoutMillis());
            connection.setReadTimeout(policy.readTimeoutMillis());
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "Tianshu-Model-Downloader/1.0");
            if (resume != null) {
                connection.setRequestProperty("Range", "bytes=" + resume.localLength() + "-");
                connection.setRequestProperty("If-Range", resume.metadata().validator());
            }
            return connection;
        } catch (IOException | RuntimeException failure) {
            close(connection);
            throw failure;
        }
    }

    private static void requireSuccessful(HttpURLConnection connection, URI candidate) throws IOException {
        int status = connection.getResponseCode();
        requireSuccessfulStatus(status, candidate);
    }

    private static void requireSuccessfulStatus(int status, URI candidate) throws IOException {
        if (status < 200 || status >= 300) {
            throw new IOException("MODEL_DOWNLOAD_HTTP_STATUS source=" + candidate + " status=" + status);
        }
    }

    private Optional<ResumeState> loadResumeState(URI candidate, Path temporary, Path metadataFile) throws IOException {
        Optional<ModelDownloadResumeMetadata> metadata = ModelDownloadResumeMetadata.read(metadataFile);
        if (metadata.isEmpty()) {
            if (Files.exists(metadataFile) || Files.exists(temporary)) {
                Files.deleteIfExists(metadataFile);
                Files.deleteIfExists(temporary);
            }
            return Optional.empty();
        }
        if (!metadata.get().source().equals(candidate)) {
            return Optional.empty();
        }
        long localLength = Files.isRegularFile(temporary) ? Files.size(temporary) : -1L;
        if (localLength <= 0L || localLength > metadata.get().totalLength()) {
            Files.deleteIfExists(metadataFile);
            Files.deleteIfExists(temporary);
            return Optional.empty();
        }
        return Optional.of(new ResumeState(metadata.get(), localLength));
    }

    private void cleanupIfNotResumable(Path temporary, Path metadataFile) throws IOException {
        Optional<ModelDownloadResumeMetadata> metadata = ModelDownloadResumeMetadata.read(metadataFile);
        long localLength = Files.isRegularFile(temporary) ? Files.size(temporary) : 0L;
        if (metadata.isEmpty() || localLength <= 0L || localLength >= metadata.get().totalLength()) {
            Files.deleteIfExists(metadataFile);
            Files.deleteIfExists(temporary);
        }
    }

    private void cleanupUntrustedPartial(Path temporary, Path metadataFile, List<URI> candidates) throws IOException {
        Optional<ModelDownloadResumeMetadata> metadata = ModelDownloadResumeMetadata.read(metadataFile);
        long localLength = Files.isRegularFile(temporary) ? Files.size(temporary) : 0L;
        boolean trusted = metadata.isPresent()
                && candidates.contains(metadata.get().source())
                && localLength > 0L
                && localLength <= metadata.get().totalLength();
        if (!trusted) {
            Files.deleteIfExists(metadataFile);
            Files.deleteIfExists(temporary);
        }
    }

    private ContentRange parseContentRange(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = CONTENT_RANGE.matcher(value.trim());
        if (!matcher.matches()) {
            return null;
        }
        try {
            long start = Long.parseLong(matcher.group(1));
            long end = Long.parseLong(matcher.group(2));
            long total = Long.parseLong(matcher.group(3));
            return start >= 0L && end >= start && total > end ? new ContentRange(start, end, total) : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private boolean completePartialConfirmed(HttpURLConnection connection, ResumeState resume) {
        if (resume.localLength() != resume.metadata().totalLength()
                || !resume.metadata().matchesResponse(connection)) {
            return false;
        }
        String value = connection.getHeaderField("Content-Range");
        if (value == null) {
            return false;
        }
        Matcher matcher = UNSATISFIED_CONTENT_RANGE.matcher(value.trim());
        if (!matcher.matches()) {
            return false;
        }
        try {
            return Long.parseLong(matcher.group(1)) == resume.metadata().totalLength();
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static void validateLength(
            URI candidate,
            long expectedLength,
            long actualLength,
            boolean requireNonEmpty
    ) throws IOException {
        if (expectedLength >= 0L && actualLength != expectedLength) {
            throw new IOException(
                    "MODEL_DOWNLOAD_LENGTH_MISMATCH source=" + candidate
                            + " expected=" + expectedLength + " actual=" + actualLength
            );
        }
        if (requireNonEmpty && actualLength == 0L) {
            throw new IOException("MODEL_DOWNLOAD_EMPTY_FILE source=" + candidate);
        }
    }

    private static void checkControl(DownloadControl control) throws DownloadCancelledException {
        if (Thread.currentThread().isInterrupted()) {
            throw new DownloadCancelledException("MODEL_DOWNLOAD_INTERRUPTED");
        }
        if (control == null) {
            return;
        }
        try {
            control.awaitReady();
        } catch (DownloadCancelledException cancelled) {
            throw cancelled;
        } catch (IOException cancelled) {
            throw new DownloadCancelledException("MODEL_DOWNLOAD_CANCELLED", cancelled);
        }
    }

    private static void awaitBackoff(long millis, DownloadControl control) throws DownloadCancelledException {
        long remaining = millis;
        while (remaining > 0L) {
            checkControl(control);
            long slice = Math.min(remaining, CONTROL_POLL_MILLIS);
            try {
                Thread.sleep(slice);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new DownloadCancelledException("MODEL_DOWNLOAD_INTERRUPTED", interrupted);
            }
            remaining -= slice;
        }
    }

    private static void moveIntoPlace(Path temporary, Path target) throws IOException {
        try {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void close(HttpURLConnection connection) {
        if (connection == null) {
            return;
        }
        activeConnections.remove(connection);
        connection.disconnect();
    }

    private static List<URI> validateCandidates(List<URI> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        List<URI> result = candidates.stream().filter(Objects::nonNull).distinct().toList();
        if (result.isEmpty()) {
            throw new IllegalArgumentException("candidates must not be empty");
        }
        for (URI candidate : result) {
            String scheme = candidate.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("candidate must use HTTP(S): " + candidate);
            }
        }
        return result;
    }

    record RetryPolicy(
            int maxAttempts,
            int connectTimeoutMillis,
            int readTimeoutMillis,
            long backoffMillis
    ) {
        RetryPolicy {
            if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
            if (connectTimeoutMillis < 1) throw new IllegalArgumentException("connectTimeoutMillis must be positive");
            if (readTimeoutMillis < 1) throw new IllegalArgumentException("readTimeoutMillis must be positive");
            if (backoffMillis < 0L) throw new IllegalArgumentException("backoffMillis must not be negative");
        }
    }

    @FunctionalInterface
    interface DownloadControl {
        void awaitReady() throws IOException;
    }

    @FunctionalInterface
    interface ProgressListener {
        void onProgress(long downloaded, long total);
    }

    static final class DownloadCancelledException extends IOException {
        DownloadCancelledException(String message) {
            super(message);
        }

        DownloadCancelledException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @FunctionalInterface
    private interface CandidateOperation<T> {
        T run(URI candidate, RetryPolicy policy) throws IOException;
    }

    private record ResumeState(ModelDownloadResumeMetadata metadata, long localLength) {
    }

    private record ContentRange(long start, long end, long total) {
    }

    private static final class ResumeRejectedException extends IOException {
        private ResumeRejectedException(String message) {
            super(message);
        }
    }
}
