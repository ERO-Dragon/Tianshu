package com.rheinmetal.tianshu.function.weaving.model;

import com.rheinmetal.tianshu.model.ModelDownloadSourcePolicy;
import com.rheinmetal.tianshu.testkit.ModelDownloadTestServer;
import com.rheinmetal.tianshu.model.HuggingFaceDownloader;
import com.rheinmetal.tianshu.model.ModelDownloadStage;
import com.rheinmetal.tianshu.model.ModelDownloadProgress;
import com.rheinmetal.tianshu.model.ModelDownloadHttpClient;
import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import com.rheinmetal.tianshu.model.lifecycle.ModelDownloadCallback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LlmModelDownloaderTest {
    @TempDir
    Path tempDir;

    @Test
    void usesCatalogSizeWhenHttpResponseDoesNotExposeContentLength() throws Exception {
        byte[] body = "chunked-model".getBytes(StandardCharsets.UTF_8);
        try (ModelDownloadTestServer server = new ModelDownloadTestServer()) {
            String filePath = "/org/model/resolve/main/model.gguf";
            server.enqueue(filePath, ModelDownloadTestServer.chunked(200, body));
            FakeGameEnvironment env = new FakeGameEnvironment();
            HuggingFaceDownloader hfDownloader = new HuggingFaceDownloader(
                    env,
                    new ModelDownloadSourcePolicy(server.baseUrl(), server.baseUrl()),
                    new ModelDownloadHttpClient(env),
                    server::baseUrl
            );
            LlmModelInfo info = new LlmModelInfo();
            info.name = "chunked-model";
            info.repoId = "org/model";
            info.modelFile = "model.gguf";
            info.downloadSizeBytes = body.length;
            List<ModelDownloadProgress> progress = new ArrayList<>();

            new LlmModelDownloader(env, hfDownloader).downloadSync(
                    info,
                    tempDir,
                    new RecordingCallback(progress),
                    () -> {}
            );

            assertEquals(new String(body, StandardCharsets.UTF_8),
                    Files.readString(tempDir.resolve("model.gguf"), StandardCharsets.UTF_8));
            assertTrue(progress.stream().anyMatch(item -> item.stage() == ModelDownloadStage.DOWNLOADING
                    && item.downloadedBytes() == 0L
                    && item.percent() == 0));
            assertTrue(progress.stream().anyMatch(item -> item.stage() == ModelDownloadStage.DOWNLOADING
                    && item.downloadedBytes() > 0L
                    && item.totalBytes() == body.length
                    && item.percent() > 0));
            assertTrue(progress.stream().anyMatch(item -> item.stage() == ModelDownloadStage.COMPLETED
                    && item.percent() == 100));
            assertFalse(progress.stream().anyMatch(item -> item.totalBytes() < 0L));
        }
    }

    private static final class RecordingCallback implements ModelDownloadCallback {
        private final List<ModelDownloadProgress> progress;

        private RecordingCallback(List<ModelDownloadProgress> progress) {
            this.progress = progress;
        }

        @Override
        public void onProgress(ModelDownloadProgress value) {
            progress.add(value);
        }

        @Override
        public void onComplete() {
        }

        @Override
        public void onError(String message) {
            throw new AssertionError(message);
        }
    }
    @Test
    void aHostThatDiesInTransitHandsTheNextAttemptToTheOtherHost() throws Exception {
        FakeGameEnvironment env = new FakeGameEnvironment();
        String deadA = "http://127.0.0.1:1";
        String deadB = "http://127.0.0.1:2";
        HuggingFaceDownloader downloader = new HuggingFaceDownloader(
                env,
                new ModelDownloadSourcePolicy(deadA, deadB),
                new ModelDownloadHttpClient(env),
                () -> deadA
        );
        Path target = tempDir.resolve("dead/model.gguf");

        assertThrows(Exception.class,
                () -> downloader.downloadSingleFile("org/model", "model.gguf", target, "main", 1));

        assertTrue(env.warnings.stream().anyMatch(warning -> warning.contains("retrying via " + deadB)),
                "the retry goes to the other host: " + env.warnings);
        assertFalse(Files.exists(target));
    }

    @Test
    void theFailureDiagnosticNamesTheHostAndTheReason() {
        java.io.IOException aggregate = new java.io.IOException("MODEL_DOWNLOAD_ALL_SOURCES_FAILED sources=2");
        aggregate.addSuppressed(new java.io.IOException(
                "MODEL_DOWNLOAD_SOURCE_FAILED source=http://127.0.0.1:1/org/model/resolve/main/model.gguf",
                new java.net.ConnectException("Connection refused")));
        aggregate.addSuppressed(new java.io.IOException(
                "MODEL_DOWNLOAD_SOURCE_FAILED source=https://hf-mirror.com/org/model/resolve/main/model.gguf",
                new java.io.IOException("MODEL_DOWNLOAD_HTTP_STATUS source=https://hf-mirror.com/x status=404")));

        String diagnostic = LlmModelDownloader.failureDiagnostic(aggregate);

        assertEquals("127.0.0.1: unreachable; hf-mirror.com: http-404", diagnostic);
    }
}
