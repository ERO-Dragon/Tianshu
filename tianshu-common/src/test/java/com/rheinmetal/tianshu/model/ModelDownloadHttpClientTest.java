package com.rheinmetal.tianshu.model;

import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelDownloadHttpClientTest {
    private static final ModelDownloadHttpClient.RetryPolicy NO_BACKOFF =
            new ModelDownloadHttpClient.RetryPolicy(1, 1_000, 1_000, 0);

    @TempDir
    Path tempDir;

    @Test
    void switchesToFallbackCandidateAndReportsProgress() throws Exception {
        try (ModelDownloadTestServer server = new ModelDownloadTestServer()) {
            server.enqueue("/primary", ModelDownloadTestServer.text(503, "unavailable"));
            server.enqueue("/fallback", ModelDownloadTestServer.text(200, "model-bytes"));
            ModelDownloadHttpClient client = client();
            AtomicLong downloaded = new AtomicLong();
            AtomicLong total = new AtomicLong();
            Path target = tempDir.resolve("model.bin");

            client.download(
                    List.of(server.uri("/primary"), server.uri("/fallback")),
                    target,
                    NO_BACKOFF,
                    () -> {},
                    (current, length) -> {
                        downloaded.set(current);
                        total.set(length);
                    }
            );

            assertEquals("model-bytes", Files.readString(target, StandardCharsets.UTF_8));
            assertEquals(1, server.requests("/primary"));
            assertEquals(1, server.requests("/fallback"));
            assertEquals(11, downloaded.get());
            assertEquals(11, total.get());
            assertFalse(Files.exists(tempDir.resolve("model.bin.downloading")));
        }
    }

    @Test
    void exhaustsRetriesForOneCandidateBeforeSwitching() throws Exception {
        try (ModelDownloadTestServer server = new ModelDownloadTestServer()) {
            server.enqueue(
                    "/primary",
                    ModelDownloadTestServer.text(503, "first"),
                    ModelDownloadTestServer.text(503, "second")
            );
            server.enqueue("/fallback", ModelDownloadTestServer.text(200, "ok"));
            ModelDownloadHttpClient client = client();

            client.download(
                    List.of(server.uri("/primary"), server.uri("/fallback")),
                    tempDir.resolve("retry.bin"),
                    new ModelDownloadHttpClient.RetryPolicy(2, 1_000, 1_000, 0),
                    () -> {},
                    null
            );

            assertEquals(2, server.requests("/primary"));
            assertEquals(1, server.requests("/fallback"));
        }
    }

    @Test
    void readsUtf8ThroughCandidateFallback() throws Exception {
        try (ModelDownloadTestServer server = new ModelDownloadTestServer()) {
            server.enqueue("/tree-primary", ModelDownloadTestServer.text(500, "failed"));
            server.enqueue("/tree-fallback", ModelDownloadTestServer.text(200, "[{\"type\":\"file\"}]"));

            String body = client().readUtf8(
                    List.of(server.uri("/tree-primary"), server.uri("/tree-fallback")),
                    NO_BACKOFF,
                    () -> {}
            );

            assertEquals("[{\"type\":\"file\"}]", body);
        }
    }

    @Test
    void lengthMismatchDoesNotReplaceExistingTargetAndCleansTemporaryFile() throws Exception {
        try (ModelDownloadTestServer server = new ModelDownloadTestServer()) {
            server.enqueue(
                    "/short",
                    ModelDownloadTestServer.declaredLength(200, "abc".getBytes(StandardCharsets.UTF_8), 10)
            );
            Path target = tempDir.resolve("stable.bin");
            Files.writeString(target, "old", StandardCharsets.UTF_8);

            assertThrows(IOException.class, () -> client().download(
                    List.of(server.uri("/short")),
                    target,
                    NO_BACKOFF,
                    () -> {},
                    null
            ));

            assertEquals("old", Files.readString(target, StandardCharsets.UTF_8));
            assertFalse(Files.exists(tempDir.resolve("stable.bin.downloading")));
        }
    }

    @Test
    void preservesFailureFromEveryCandidate() throws Exception {
        try (ModelDownloadTestServer server = new ModelDownloadTestServer()) {
            server.enqueue("/one", ModelDownloadTestServer.text(500, "one"));
            server.enqueue("/two", ModelDownloadTestServer.text(502, "two"));

            IOException failure = assertThrows(IOException.class, () -> client().readUtf8(
                    List.of(server.uri("/one"), server.uri("/two")),
                    NO_BACKOFF,
                    () -> {}
            ));

            assertEquals(2, failure.getSuppressed().length);
            assertTrue(failure.getMessage().contains("MODEL_DOWNLOAD_ALL_SOURCES_FAILED"));
        }
    }

    @Test
    void cancellationDoesNotTryAnyCandidate() throws Exception {
        try (ModelDownloadTestServer server = new ModelDownloadTestServer()) {
            server.enqueue("/one", ModelDownloadTestServer.text(200, "one"));
            server.enqueue("/two", ModelDownloadTestServer.text(200, "two"));

            assertThrows(ModelDownloadHttpClient.DownloadCancelledException.class, () -> client().download(
                    List.of(server.uri("/one"), server.uri("/two")),
                    tempDir.resolve("cancelled.bin"),
                    NO_BACKOFF,
                    () -> { throw new ModelDownloadHttpClient.DownloadCancelledException("cancelled"); },
                    null
            ));

            assertEquals(0, server.requests("/one"));
            assertEquals(0, server.requests("/two"));
            assertFalse(Files.exists(tempDir.resolve("cancelled.bin")));
        }
    }

    @Test
    void resumesTrustedPartialOnTheNextAttempt() throws Exception {
        try (ModelDownloadTestServer server = new ModelDownloadTestServer()) {
            server.enqueue(
                    "/resume",
                    ModelDownloadTestServer.resumable(200, "abcd".getBytes(StandardCharsets.UTF_8), 10, "\"v1\"", null),
                    ModelDownloadTestServer.resumable(206, "efghij".getBytes(StandardCharsets.UTF_8), 6, "\"v1\"", "bytes 4-9/10")
            );
            Path target = tempDir.resolve("resume.bin");

            client().download(
                    List.of(server.uri("/resume")),
                    target,
                    new ModelDownloadHttpClient.RetryPolicy(2, 1_000, 1_000, 0),
                    () -> {},
                    null
            );

            assertEquals("abcdefghij", Files.readString(target, StandardCharsets.UTF_8));
            assertEquals("bytes=4-", server.lastRequestHeader("/resume", "Range"));
            assertEquals("\"v1\"", server.lastRequestHeader("/resume", "If-Range"));
            assertFalse(Files.exists(tempDir.resolve("resume.bin.downloading")));
            assertFalse(Files.exists(tempDir.resolve("resume.bin.downloading.meta")));
        }
    }

    @Test
    void resumesTrustedPartialWithANewClientInstance() throws Exception {
        try (ModelDownloadTestServer server = new ModelDownloadTestServer()) {
            server.enqueue(
                    "/restart",
                    ModelDownloadTestServer.resumable(200, "abcd".getBytes(StandardCharsets.UTF_8), 10, "\"v1\"", null)
            );
            Path target = tempDir.resolve("restart.bin");

            assertThrows(IOException.class, () -> client().download(
                    List.of(server.uri("/restart")),
                    target,
                    NO_BACKOFF,
                    () -> {},
                    null
            ));
            assertTrue(Files.exists(tempDir.resolve("restart.bin.downloading")));
            assertTrue(Files.exists(tempDir.resolve("restart.bin.downloading.meta")));

            server.enqueue(
                    "/restart",
                    ModelDownloadTestServer.resumable(206, "efghij".getBytes(StandardCharsets.UTF_8), 6, "\"v1\"", "bytes 4-9/10")
            );
            client().download(
                    List.of(server.uri("/restart")),
                    target,
                    NO_BACKOFF,
                    () -> {},
                    null
            );

            assertEquals("abcdefghij", Files.readString(target, StandardCharsets.UTF_8));
            assertEquals("bytes=4-", server.lastRequestHeader("/restart", "Range"));
        }
    }

    @Test
    void rangeIgnoredWithFullResponseRestartsWithoutAppending() throws Exception {
        try (ModelDownloadTestServer server = new ModelDownloadTestServer()) {
            Path target = tempDir.resolve("range-ignored.bin");
            server.enqueue("/range-ignored",
                    ModelDownloadTestServer.resumable(200, "old-".getBytes(StandardCharsets.UTF_8), 10, "\"v1\"", null));
            assertThrows(IOException.class, () -> client().download(
                    List.of(server.uri("/range-ignored")), target, NO_BACKOFF, () -> {}, null));

            server.enqueue("/range-ignored",
                    ModelDownloadTestServer.resumable(200, "new-content".getBytes(StandardCharsets.UTF_8), 11, "\"v2\"", null));
            client().download(List.of(server.uri("/range-ignored")), target, NO_BACKOFF, () -> {}, null);

            assertEquals("new-content", Files.readString(target, StandardCharsets.UTF_8));
            assertEquals("bytes=4-", server.lastRequestHeader("/range-ignored", "Range"));
        }
    }

    @Test
    void corruptMetadataIsDiscardedBeforeFullDownload() throws Exception {
        try (ModelDownloadTestServer server = new ModelDownloadTestServer()) {
            Path target = tempDir.resolve("corrupt.bin");
            Files.writeString(tempDir.resolve("corrupt.bin.downloading"), "untrusted", StandardCharsets.UTF_8);
            Files.writeString(tempDir.resolve("corrupt.bin.downloading.meta"), "not-json", StandardCharsets.UTF_8);
            server.enqueue("/corrupt", ModelDownloadTestServer.text(200, "clean"));

            client().download(List.of(server.uri("/corrupt")), target, NO_BACKOFF, () -> {}, null);

            assertEquals("clean", Files.readString(target, StandardCharsets.UTF_8));
            assertEquals("", server.lastRequestHeader("/corrupt", "Range"));
        }
    }

    @Test
    void completeTrustedPartialCommitsAfterRangeNotSatisfiable() throws Exception {
        try (ModelDownloadTestServer server = new ModelDownloadTestServer()) {
            Path target = tempDir.resolve("complete.bin");
            Path partial = tempDir.resolve("complete.bin.downloading");
            Path metadata = tempDir.resolve("complete.bin.downloading.meta");
            URI source = server.uri("/complete");
            Files.writeString(partial, "complete!!", StandardCharsets.UTF_8);
            new ModelDownloadResumeMetadata(
                    source,
                    ModelDownloadResumeMetadata.ValidatorKind.ETAG,
                    "\"v1\"",
                    10
            ).write(metadata);
            server.enqueue("/complete",
                    ModelDownloadTestServer.resumable(416, new byte[0], 0, "\"v1\"", "bytes */10"));

            client().download(List.of(source), target, NO_BACKOFF, () -> {}, null);

            assertEquals("complete!!", Files.readString(target, StandardCharsets.UTF_8));
            assertEquals("bytes=10-", server.lastRequestHeader("/complete", "Range"));
            assertFalse(Files.exists(partial));
            assertFalse(Files.exists(metadata));
        }
    }

    @Test
    void cancellationKeepsOnlyTrustedPartialForNextStart() throws Exception {
        try (ModelDownloadTestServer server = new ModelDownloadTestServer()) {
            byte[] body = new byte[20_000];
            java.util.Arrays.fill(body, (byte) 'x');
            server.enqueue("/cancel-resume",
                    ModelDownloadTestServer.resumable(200, body, body.length, "\"v1\"", null));
            Path target = tempDir.resolve("cancel-resume.bin");
            java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();

            assertThrows(ModelDownloadHttpClient.DownloadCancelledException.class, () -> client().download(
                    List.of(server.uri("/cancel-resume")),
                    target,
                    NO_BACKOFF,
                    () -> {
                        if (checks.incrementAndGet() >= 3) {
                            throw new ModelDownloadHttpClient.DownloadCancelledException("cancelled");
                        }
                    },
                    null
            ));

            assertTrue(Files.size(tempDir.resolve("cancel-resume.bin.downloading")) > 0L);
            assertTrue(Files.exists(tempDir.resolve("cancel-resume.bin.downloading.meta")));
            assertFalse(Files.exists(target));
        }
    }

    @Test
    void changedValidatorRejectsPartialAndRestartsSameSource() throws Exception {
        try (ModelDownloadTestServer server = new ModelDownloadTestServer()) {
            Path target = tempDir.resolve("changed.bin");
            server.enqueue("/changed",
                    ModelDownloadTestServer.resumable(200, "old-".getBytes(StandardCharsets.UTF_8), 10, "\"v1\"", null));
            assertThrows(IOException.class, () -> client().download(
                    List.of(server.uri("/changed")), target, NO_BACKOFF, () -> {}, null));

            server.enqueue(
                    "/changed",
                    ModelDownloadTestServer.resumable(206, "xxxxxx".getBytes(StandardCharsets.UTF_8), 6, "\"v2\"", "bytes 4-9/10"),
                    ModelDownloadTestServer.resumable(200, "new-content".getBytes(StandardCharsets.UTF_8), 11, "\"v2\"", null)
            );
            client().download(List.of(server.uri("/changed")), target, NO_BACKOFF, () -> {}, null);

            assertEquals("new-content", Files.readString(target, StandardCharsets.UTF_8));
            assertEquals(3, server.requests("/changed"));
        }
    }

    @Test
    void lastModifiedCanValidateResumeWhenStrongEtagIsAbsent() throws Exception {
        try (ModelDownloadTestServer server = new ModelDownloadTestServer()) {
            String lastModified = "Wed, 15 Jul 2026 10:00:00 GMT";
            server.enqueue(
                    "/last-modified",
                    ModelDownloadTestServer.resumable(200, "abcd".getBytes(StandardCharsets.UTF_8), 10, "", null)
                            .withHeader("Last-Modified", lastModified),
                    ModelDownloadTestServer.resumable(206, "efghij".getBytes(StandardCharsets.UTF_8), 6, "", "bytes 4-9/10")
                            .withHeader("Last-Modified", lastModified)
            );
            Path target = tempDir.resolve("last-modified.bin");

            client().download(
                    List.of(server.uri("/last-modified")),
                    target,
                    new ModelDownloadHttpClient.RetryPolicy(2, 1_000, 1_000, 0),
                    () -> {},
                    null
            );

            assertEquals("abcdefghij", Files.readString(target, StandardCharsets.UTF_8));
            assertEquals(lastModified, server.lastRequestHeader("/last-modified", "If-Range"));
        }
    }

    @Test
    void fallbackSourceReplacesRatherThanMixesPrimaryPartial() throws Exception {
        try (ModelDownloadTestServer server = new ModelDownloadTestServer()) {
            Path target = tempDir.resolve("fallback-resume.bin");
            server.enqueue("/partial-primary",
                    ModelDownloadTestServer.resumable(200, "old-".getBytes(StandardCharsets.UTF_8), 10, "\"p1\"", null));
            assertThrows(IOException.class, () -> client().download(
                    List.of(server.uri("/partial-primary")), target, NO_BACKOFF, () -> {}, null));

            server.enqueue("/partial-primary", ModelDownloadTestServer.text(503, "unavailable"));
            server.enqueue("/clean-fallback", ModelDownloadTestServer.text(200, "fallback"));
            client().download(
                    List.of(server.uri("/partial-primary"), server.uri("/clean-fallback")),
                    target,
                    NO_BACKOFF,
                    () -> {},
                    null
            );

            assertEquals("fallback", Files.readString(target, StandardCharsets.UTF_8));
            assertFalse(Files.exists(tempDir.resolve("fallback-resume.bin.downloading.meta")));
        }
    }

    private ModelDownloadHttpClient client() {
        return new ModelDownloadHttpClient(new TestLlmSupport.FakeGameEnvironment());
    }
}
