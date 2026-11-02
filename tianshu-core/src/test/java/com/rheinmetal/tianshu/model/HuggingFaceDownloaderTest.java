package com.rheinmetal.tianshu.model;

import com.rheinmetal.tianshu.testkit.ModelDownloadTestServer;
import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

            FakeGameEnvironment env = new FakeGameEnvironment();
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
            FakeGameEnvironment env = new FakeGameEnvironment();
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

    /**
     * A body shorter than the size the repository declared must not be accepted. The transport can only see
     * the length the server itself claimed, so without this check a truncated proxy response would install a
     * model that fails much later, inside the runtime.
     */
    @Test
    void rejectsAFileShorterThanTheDeclaredSizeAndDeletesIt() throws Exception {
        try (ModelDownloadTestServer official = new ModelDownloadTestServer();
             ModelDownloadTestServer mirror = new ModelDownloadTestServer()) {
            official.enqueue("/api/models/org/model/tree/main", ModelDownloadTestServer.text(200,
                    "[{\"type\":\"file\",\"path\":\"model.onnx\",\"size\":40}]"));
            official.enqueue("/org/model/resolve/main/model.onnx",
                    ModelDownloadTestServer.text(200, "short"));

            IOException failure = assertThrows(IOException.class, () -> downloader(official, mirror)
                    .downloadModelFiles("org/model", tempDir, "main", false, 1));

            assertTrue(failure.getMessage().startsWith("MODEL_FILE_SIZE_MISMATCH"), failure.getMessage());
            assertFalse(Files.exists(tempDir.resolve("model.onnx")),
                    "a file that failed verification must be removed so a retry re-downloads it");
        }
    }

    /**
     * A published digest is the only fact that says what the bytes are, so a body that arrives complete but
     * wrong must not be installed either.
     */
    @Test
    void rejectsAFileWhoseDigestDoesNotMatchAndDeletesIt() throws Exception {
        try (ModelDownloadTestServer official = new ModelDownloadTestServer();
             ModelDownloadTestServer mirror = new ModelDownloadTestServer()) {
            String body = "model-content";
            official.enqueue("/api/models/org/model/tree/main", ModelDownloadTestServer.text(200,
                    "[{\"type\":\"file\",\"path\":\"model.onnx\",\"size\":" + body.length() + ","
                            + "\"lfs\":{\"oid\":\"" + "0".repeat(64) + "\",\"size\":" + body.length() + "}}]"));
            official.enqueue("/org/model/resolve/main/model.onnx",
                    ModelDownloadTestServer.text(200, body));

            IOException failure = assertThrows(IOException.class, () -> downloader(official, mirror)
                    .downloadModelFiles("org/model", tempDir, "main", false, 1));

            assertTrue(failure.getMessage().startsWith("MODEL_FILE_CHECKSUM_MISMATCH"), failure.getMessage());
            assertFalse(Files.exists(tempDir.resolve("model.onnx")),
                    "a checksum mismatch must not leave the file where a retry would skip it");
        }
    }

    @Test
    void acceptsAFileWhoseDigestMatches() throws Exception {
        try (ModelDownloadTestServer official = new ModelDownloadTestServer();
             ModelDownloadTestServer mirror = new ModelDownloadTestServer()) {
            String body = "model-content";
            String digest = sha256(body);
            official.enqueue("/api/models/org/model/tree/main", ModelDownloadTestServer.text(200,
                    "[{\"type\":\"file\",\"path\":\"model.onnx\",\"size\":" + body.length() + ","
                            + "\"lfs\":{\"oid\":\"" + digest + "\",\"size\":" + body.length() + "}}]"));
            official.enqueue("/org/model/resolve/main/model.onnx", ModelDownloadTestServer.text(200, body));

            downloader(official, mirror).downloadModelFiles("org/model", tempDir, "main", false, 1);

            assertEquals(body, Files.readString(tempDir.resolve("model.onnx"), StandardCharsets.UTF_8));
        }
    }

    private static HuggingFaceDownloader downloader(ModelDownloadTestServer official, ModelDownloadTestServer mirror) {
        FakeGameEnvironment env = new FakeGameEnvironment();
        return new HuggingFaceDownloader(
                env,
                new ModelDownloadSourcePolicy(official.baseUrl(), mirror.baseUrl()),
                new ModelDownloadHttpClient(env),
                official::baseUrl
        );
    }

    private static String sha256(String text) throws Exception {
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(64);
        for (byte value : hash) {
            hex.append(String.format("%02x", value));
        }
        return hex.toString();
    }
}
