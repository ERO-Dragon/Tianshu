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
import java.util.concurrent.ConcurrentHashMap;

/** Executes model-download HTTP requests while keeping source selection outside transport. */
final class ModelDownloadHttpClient {
    private static final int BUFFER_SIZE = 8192;
    private static final long CONTROL_POLL_MILLIS = 100L;

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
        try {
            executeCandidates(candidates, policy, control, (candidate, retryPolicy) -> {
                Files.deleteIfExists(temporary);
                try {
                    downloadOnce(candidate, temporary, retryPolicy, control, progress);
                    moveIntoPlace(temporary, absoluteTarget);
                    return Boolean.TRUE;
                } catch (IOException failure) {
                    Files.deleteIfExists(temporary);
                    throw failure;
                }
            });
        } finally {
            Files.deleteIfExists(temporary);
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
            RetryPolicy policy,
            DownloadControl control,
            ProgressListener progress
    ) throws IOException {
        HttpURLConnection connection = open(candidate, policy);
        try {
            requireSuccessful(connection, candidate);
            long expectedLength = connection.getContentLengthLong();
            long downloaded = 0L;
            try (InputStream input = connection.getInputStream();
                 OutputStream output = Files.newOutputStream(
                         temporary,
                         StandardOpenOption.CREATE,
                         StandardOpenOption.TRUNCATE_EXISTING,
                         StandardOpenOption.WRITE
                 )) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    checkControl(control);
                    output.write(buffer, 0, read);
                    downloaded += read;
                    if (progress != null) {
                        progress.onProgress(downloaded, expectedLength);
                    }
                }
            }
            validateLength(candidate, expectedLength, downloaded, true);
        } finally {
            close(connection);
        }
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
        HttpURLConnection connection = (HttpURLConnection) candidate.toURL().openConnection();
        activeConnections.add(connection);
        try {
            connection.setConnectTimeout(policy.connectTimeoutMillis());
            connection.setReadTimeout(policy.readTimeoutMillis());
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "Tianshu-Model-Downloader/1.0");
            return connection;
        } catch (IOException | RuntimeException failure) {
            close(connection);
            throw failure;
        }
    }

    private static void requireSuccessful(HttpURLConnection connection, URI candidate) throws IOException {
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            throw new IOException("MODEL_DOWNLOAD_HTTP_STATUS source=" + candidate + " status=" + status);
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
}
