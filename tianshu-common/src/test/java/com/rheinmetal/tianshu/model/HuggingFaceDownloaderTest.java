package com.rheinmetal.tianshu.model;

import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HuggingFaceDownloaderTest {
    @TempDir
    Path tempDir;

    @Test
    void fallsBackIndependentlyForRepositoryTreeAndFile() throws Exception {
        try (ModelDownloadTestServer official = new ModelDownloadTestServer();
             ModelDownloadTestServer mirror = new ModelDownloadTestServer()) {
            String treePath = "/api/models/org/model/tree/rev%20one";
            String filePath = "/org/model/resolve/rev%20one/folder/model%20file.onnx";
            official.enqueue(treePath, ModelDownloadTestServer.text(503, "official tree unavailable"));
            mirror.enqueue(treePath, ModelDownloadTestServer.text(
                    200,
                    "[{\"type\":\"directory\",\"path\":\"folder\"},"
                            + "{\"type\":\"file\",\"path\":\"folder/model file.onnx\"}]"
            ));
            official.enqueue(filePath, ModelDownloadTestServer.text(503, "official file unavailable"));
            mirror.enqueue(filePath, ModelDownloadTestServer.text(200, "model-content"));

            TestLlmSupport.FakeGameEnvironment env = new TestLlmSupport.FakeGameEnvironment();
            ModelDownloadSourcePolicy sources = new ModelDownloadSourcePolicy(
                    official.baseUrl(),
                    mirror.baseUrl()
            );
            HuggingFaceDownloader downloader = new HuggingFaceDownloader(
                    env,
                    sources,
                    new ModelDownloadHttpClient(env),
                    official::baseUrl
            );
            AtomicInteger resolvedFiles = new AtomicInteger();

            downloader.downloadModelFiles(
                    "org/model",
                    tempDir,
                    "rev one",
                    false,
                    1,
                    () -> {},
                    new HuggingFaceDownloader.DownloadProgressListener() {
                        @Override
                        public void onFileListResolved(int totalFiles) {
                            resolvedFiles.set(totalFiles);
                        }
                    }
            );

            assertEquals("model-content", Files.readString(
                    tempDir.resolve("folder/model file.onnx"),
                    StandardCharsets.UTF_8
            ));
            assertEquals(1, resolvedFiles.get());
            assertEquals(1, official.requests(treePath));
            assertEquals(1, mirror.requests(treePath));
            assertEquals(1, official.requests(filePath));
            assertEquals(1, mirror.requests(filePath));
        }
    }

    @Test
    void skipExistingDoesNotRequestFileAgain() throws Exception {
        try (ModelDownloadTestServer official = new ModelDownloadTestServer();
             ModelDownloadTestServer mirror = new ModelDownloadTestServer()) {
            String treePath = "/api/models/org/model/tree/main";
            String filePath = "/org/model/resolve/main/model.onnx";
            official.enqueue(treePath, ModelDownloadTestServer.text(
                    200,
                    "[{\"type\":\"file\",\"path\":\"model.onnx\"}]"
            ));
            Files.writeString(tempDir.resolve("model.onnx"), "existing", StandardCharsets.UTF_8);
            TestLlmSupport.FakeGameEnvironment env = new TestLlmSupport.FakeGameEnvironment();
            HuggingFaceDownloader downloader = new HuggingFaceDownloader(
                    env,
                    new ModelDownloadSourcePolicy(official.baseUrl(), mirror.baseUrl()),
                    new ModelDownloadHttpClient(env),
                    official::baseUrl
            );

            downloader.downloadModelFiles("org/model", tempDir, "main", true, 1);

            assertEquals("existing", Files.readString(tempDir.resolve("model.onnx"), StandardCharsets.UTF_8));
            assertEquals(0, official.requests(filePath));
            assertEquals(0, mirror.requests(filePath));
        }
    }
}
