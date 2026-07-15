package com.rheinmetal.tianshu.model;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelArchiveDownloaderTest {
    private static final Path FACADE_SOURCE = Path.of(
            "src/main/java/com/rheinmetal/tianshu/model/ModelArchiveDownloader.java"
    );

    @TempDir
    Path tempDir;

    @Test
    void ttsArchiveDownloadUsesDedicatedUriFacadeWithoutLegacyUrlApi() throws Exception {
        assertTrue(Files.isRegularFile(FACADE_SOURCE), "model domain must own a narrow archive downloader facade");

        String modelInfo = source("src/main/java/com/rheinmetal/tianshu/model/TtsModelInfo.java");
        String coordinator = source("src/main/java/com/rheinmetal/tianshu/function/tts/download/TtsModelDownloadCoordinator.java");
        String service = source("src/main/java/com/rheinmetal/tianshu/function/tts/TtsModelService.java");
        String catalog = source("src/main/resources/com/rheinmetal/tianshu/constant/tts-model.json");

        assertTrue(modelInfo.contains("downloadUri"));
        assertTrue(coordinator.contains("ModelArchiveDownloader"));
        assertFalse(modelInfo.contains("downloadUrl"));
        assertFalse(coordinator.contains("downloadUrl"));
        assertFalse(service.contains("downloadUrl"));
        assertFalse(catalog.contains("downloadUrl"));
        assertFalse(coordinator.contains("new URL("));
    }

    @Test
    void proxyFirstFailureFallsBackToDirectUriAndReportsProgress() throws Exception {
        try (ModelDownloadTestServer server = new ModelDownloadTestServer()) {
            URI direct = server.uri("/direct/model.tar.bz2");
            URI proxyBase = server.uri("/proxy");
            String proxyPath = proxyBase.getPath() + "/" + direct;
            server.enqueue(proxyPath, ModelDownloadTestServer.text(503, "proxy unavailable"));
            server.enqueue("/direct/model.tar.bz2", ModelDownloadTestServer.text(200, "archive-bytes"));
            AtomicLong downloaded = new AtomicLong();
            AtomicLong total = new AtomicLong();
            Path target = tempDir.resolve("model.tar.bz2");

            downloader().downloadGithubArchive(
                    direct,
                    proxyBase,
                    true,
                    target,
                    noBackoff(),
                    () -> {},
                    (current, length) -> {
                        downloaded.set(current);
                        total.set(length);
                    }
            );

            assertEquals("archive-bytes", Files.readString(target, StandardCharsets.UTF_8));
            assertEquals(1, server.requests(proxyPath));
            assertEquals(1, server.requests("/direct/model.tar.bz2"));
            assertEquals(13L, downloaded.get());
            assertEquals(13L, total.get());
        }
    }

    @Test
    void cancellationBeforeDownloadDoesNotOpenAnySource() throws Exception {
        try (ModelDownloadTestServer server = new ModelDownloadTestServer()) {
            URI direct = server.uri("/direct/model.tar.bz2");
            server.enqueue("/direct/model.tar.bz2", ModelDownloadTestServer.text(200, "archive"));

            assertThrows(IOException.class, () -> downloader().downloadGithubArchive(
                    direct,
                    null,
                    false,
                    tempDir.resolve("cancelled.tar.bz2"),
                    noBackoff(),
                    () -> { throw new IOException("cancelled"); },
                    null
            ));

            assertEquals(0, server.requests("/direct/model.tar.bz2"));
            assertFalse(Files.exists(tempDir.resolve("cancelled.tar.bz2")));
        }
    }

    private static ModelArchiveDownloader downloader() {
        return new ModelArchiveDownloader(new FakeGameEnvironment());
    }

    private static ModelArchiveDownloader.RetryPolicy noBackoff() {
        return new ModelArchiveDownloader.RetryPolicy(1, 1_000, 1_000, 0L);
    }

    private static String source(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    private static final class FakeGameEnvironment implements IGameEnvironment {
        @Override
        public void displayMessageToPlayer(String message) {
        }

        @Override
        public void executeOnMainThread(Runnable task) {
            task.run();
        }

        @Override
        public Path getGameDirectory() {
            return Path.of(".");
        }

        @Override
        public boolean isClientSide() {
            return true;
        }

        @Override
        public void openFolder(Path dir) {
        }

        @Override
        public void info(String msg) {
        }

        @Override
        public void warn(String msg) {
        }

        @Override
        public void error(String msg, Throwable t) {
        }

        @Override
        public com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink diagnostics() {
            return com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink.NOOP;
        }
    }
}
