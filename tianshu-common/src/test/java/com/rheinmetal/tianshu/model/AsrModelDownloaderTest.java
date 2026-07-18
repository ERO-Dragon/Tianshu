package com.rheinmetal.tianshu.model;

import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AsrModelDownloaderTest {
    @TempDir
    Path tempDir;

    @Test
    void directFirstFallsBackToGithubProxyAndMaterializesNestedRequiredFile() throws Exception {
        try (ModelDownloadTestServer direct = new ModelDownloadTestServer();
             ModelDownloadTestServer proxy = new ModelDownloadTestServer()) {
            String directPath = "/archive.zip";
            String directUrl = direct.uri(directPath).toString();
            String proxyPath = proxyPath(proxy, directUrl);
            direct.enqueue(directPath, ModelDownloadTestServer.text(503, "direct unavailable"));
            proxy.enqueue(proxyPath, ModelDownloadTestServer.bytes(
                    200,
                    zip("nested/model.onnx", "asr-model")
            ));
            AsrModelInfo info = archiveInfo(directUrl, "model.onnx");
            Path target = tempDir.resolve("direct-first-model");
            RecordingCallback callback = new RecordingCallback();

            downloader(direct::baseUrl, () -> true).downloadSync(
                    info,
                    target,
                    proxy.baseUrl(),
                    callback,
                    () -> {}
            );

            assertEquals("asr-model", Files.readString(target.resolve("model.onnx"), StandardCharsets.UTF_8));
            assertEquals(5, direct.requests(directPath));
            assertEquals(1, proxy.requests(proxyPath));
            assertEquals(1, callback.completed.get());
        }
    }

    @Test
    void proxyFirstFallsBackToGithubDirect() throws Exception {
        try (ModelDownloadTestServer direct = new ModelDownloadTestServer();
             ModelDownloadTestServer proxy = new ModelDownloadTestServer()) {
            String directPath = "/archive.zip";
            String directUrl = direct.uri(directPath).toString();
            String proxyPath = proxyPath(proxy, directUrl);
            proxy.enqueue(proxyPath, ModelDownloadTestServer.text(502, "proxy unavailable"));
            direct.enqueue(directPath, ModelDownloadTestServer.bytes(
                    200,
                    zip("model.onnx", "direct-model")
            ));

            downloader(direct::baseUrl, () -> false).downloadSync(
                    archiveInfo(directUrl, "model.onnx"),
                    tempDir.resolve("proxy-first-model"),
                    proxy.baseUrl(),
                    new RecordingCallback(),
                    () -> {}
            );

            assertEquals(5, proxy.requests(proxyPath));
            assertEquals(1, direct.requests(directPath));
        }
    }

    @Test
    void missingRequiredFileKeepsOldTargetAndCleansWorkingPaths() throws Exception {
        try (ModelDownloadTestServer direct = new ModelDownloadTestServer()) {
            String directUrl = direct.uri("/archive.zip").toString();
            direct.enqueue("/archive.zip", ModelDownloadTestServer.bytes(
                    200,
                    zip("different.onnx", "wrong")
            ));
            Path target = tempDir.resolve("stable-model");
            Files.createDirectories(target);
            Files.writeString(target.resolve("old.marker"), "stable", StandardCharsets.UTF_8);

            assertThrows(IOException.class, () -> downloader(direct::baseUrl, () -> true).downloadSync(
                    archiveInfo(directUrl, "required.onnx"),
                    target,
                    "",
                    new RecordingCallback(),
                    () -> {}
            ));

            assertEquals("stable", Files.readString(target.resolve("old.marker"), StandardCharsets.UTF_8));
            assertFalse(Files.exists(tempDir.resolve("stable-model-staging")));
            assertFalse(Files.exists(tempDir.resolve("stable-model-extract")));
            assertFalse(Files.exists(tempDir.resolve("stable-model.zip")));
        }
    }

    @Test
    void huggingFaceRequiredFileFallsBackForTreeAndFile() throws Exception {
        try (ModelDownloadTestServer official = new ModelDownloadTestServer();
             ModelDownloadTestServer mirror = new ModelDownloadTestServer()) {
            String treePath = "/api/models/org/asr/tree/main";
            String filePath = "/org/asr/resolve/main/nested/model.onnx";
            official.enqueue(treePath, ModelDownloadTestServer.text(500, "tree failed"));
            mirror.enqueue(treePath, ModelDownloadTestServer.text(
                    200,
                    "[{\"type\":\"file\",\"path\":\"nested/model.onnx\"}]"
            ));
            official.enqueue(filePath, ModelDownloadTestServer.text(503, "file failed"));
            mirror.enqueue(filePath, ModelDownloadTestServer.text(200, "hf-asr"));
            TestLlmSupport.FakeGameEnvironment env = new TestLlmSupport.FakeGameEnvironment();
            AsrModelDownloader downloader = new AsrModelDownloader(
                    env,
                    new ModelDownloadSourcePolicy(official.baseUrl(), mirror.baseUrl()),
                    new ModelDownloadHttpClient(env),
                    official::baseUrl,
                    () -> true
            );
            AsrModelInfo info = new AsrModelInfo();
            info.name = "hf-asr";
            info.id = "org/asr";
            info.modelFiles = List.of("model.onnx");

            Path target = tempDir.resolve("hf-model");
            downloader.downloadSync(info, target, "", new RecordingCallback(), () -> {});

            assertEquals("hf-asr", Files.readString(target.resolve("model.onnx"), StandardCharsets.UTF_8));
            assertEquals(1, official.requests(treePath));
            assertEquals(1, mirror.requests(treePath));
            assertEquals(3, official.requests(filePath));
            assertEquals(1, mirror.requests(filePath));
        }
    }

    private AsrModelDownloader downloader(
            Supplier<String> preferredHfBase,
            BooleanSupplier githubReachable
    ) {
        TestLlmSupport.FakeGameEnvironment env = new TestLlmSupport.FakeGameEnvironment();
        return new AsrModelDownloader(
                env,
                new ModelDownloadSourcePolicy("http://official.test", "http://mirror.test"),
                new ModelDownloadHttpClient(env),
                preferredHfBase,
                githubReachable
        );
    }

    private static AsrModelInfo archiveInfo(String downloadUrl, String requiredFile) {
        AsrModelInfo info = new AsrModelInfo();
        info.name = "archive-asr";
        info.downloadUrl = downloadUrl;
        info.modelFiles = List.of(requiredFile);
        return info;
    }

    private static String proxyPath(ModelDownloadTestServer proxy, String directUrl) {
        String proxied = proxy.baseUrl() + "/" + directUrl;
        return java.net.URI.create(proxied).getRawPath();
    }

    private static byte[] zip(String entryName, String content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static final class RecordingCallback implements AsrModelDownloader.DownloadProgressCallback {
        private final AtomicInteger completed = new AtomicInteger();

        @Override
        public void onProgress(ModelDownloadProgress progress) {
        }

        @Override
        public void onComplete() {
            completed.incrementAndGet();
        }

        @Override
        public void onError(String message) {
        }
    }
}
