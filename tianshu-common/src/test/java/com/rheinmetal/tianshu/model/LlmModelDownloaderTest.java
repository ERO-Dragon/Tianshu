package com.rheinmetal.tianshu.model;

import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
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

class LlmModelDownloaderTest {
    @TempDir
    Path tempDir;

    @Test
    void usesCatalogSizeWhenHttpResponseDoesNotExposeContentLength() throws Exception {
        byte[] body = "chunked-model".getBytes(StandardCharsets.UTF_8);
        try (ModelDownloadTestServer server = new ModelDownloadTestServer()) {
            String filePath = "/org/model/resolve/main/model.gguf";
            server.enqueue(filePath, ModelDownloadTestServer.chunked(200, body));
            TestLlmSupport.FakeGameEnvironment env = new TestLlmSupport.FakeGameEnvironment();
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

    private static final class RecordingCallback implements LlmModelDownloader.DownloadProgressCallback {
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
}
