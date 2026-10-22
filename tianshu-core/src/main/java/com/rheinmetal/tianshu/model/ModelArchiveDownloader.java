package com.rheinmetal.tianshu.model;

import com.rheinmetal.tianshu.api.IGameEnvironment;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Narrow model-domain transport for downloading an archive from direct/proxy URI candidates. */
public final class ModelArchiveDownloader {
    private final ModelDownloadHttpClient http;

    public ModelArchiveDownloader(IGameEnvironment env) {
        this.http = new ModelDownloadHttpClient(Objects.requireNonNull(env, "env"));
    }

    public void downloadGithubArchive(
            URI directUri,
            URI proxyBaseUri,
            boolean proxyFirst,
            Path target,
            RetryPolicy policy,
            DownloadControl control,
            ProgressListener progress
    ) throws IOException {
        Objects.requireNonNull(directUri, "directUri");
        Objects.requireNonNull(target, "target");
        RetryPolicy retryPolicy = Objects.requireNonNull(policy, "policy");
        List<URI> candidates = ModelDownloadSourcePolicy.githubArchiveCandidates(
                directUri.toString(),
                proxyBaseUri == null ? null : proxyBaseUri.toString(),
                proxyFirst
        );
        http.download(
                candidates,
                target,
                new ModelDownloadHttpClient.RetryPolicy(
                        retryPolicy.maxAttempts(),
                        retryPolicy.connectTimeoutMillis(),
                        retryPolicy.readTimeoutMillis(),
                        retryPolicy.backoffMillis()
                ),
                control == null ? null : control::awaitReady,
                progress == null ? null : progress::onProgress
        );
    }

    public void cancelActiveTransfers() {
        http.cancelActiveTransfers();
    }

    public record RetryPolicy(
            int maxAttempts,
            int connectTimeoutMillis,
            int readTimeoutMillis,
            long backoffMillis
    ) {
        public RetryPolicy {
            if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
            if (connectTimeoutMillis < 1) throw new IllegalArgumentException("connectTimeoutMillis must be positive");
            if (readTimeoutMillis < 1) throw new IllegalArgumentException("readTimeoutMillis must be positive");
            if (backoffMillis < 0L) throw new IllegalArgumentException("backoffMillis must not be negative");
        }
    }

    @FunctionalInterface
    public interface DownloadControl {
        void awaitReady() throws IOException;
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(long downloaded, long total);
    }
}
