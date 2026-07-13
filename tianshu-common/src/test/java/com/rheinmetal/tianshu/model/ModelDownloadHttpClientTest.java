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

    private ModelDownloadHttpClient client() {
        return new ModelDownloadHttpClient(new TestLlmSupport.FakeGameEnvironment());
    }
}
