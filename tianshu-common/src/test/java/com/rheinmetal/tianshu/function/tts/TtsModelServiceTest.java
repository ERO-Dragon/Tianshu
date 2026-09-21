package com.rheinmetal.tianshu.function.tts;

import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import com.rheinmetal.tianshu.model.ModelDownloadProgress;
import com.rheinmetal.tianshu.model.TtsModelInfo;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutorManager;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsModelServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void startupCleanupKeepsPartialStagingForResume() throws Exception {
        TestLlmSupport.FakeConfig config = new TestLlmSupport.FakeConfig(tempDir);
        Path partial = config.getTtsBasePath()
                .resolve("model")
                .resolve("recovery-staging")
                .resolve("model.bin.downloading");
        Files.createDirectories(partial.getParent());
        Files.writeString(partial, "partial", StandardCharsets.UTF_8);

        try (ProtocolExecutorManager executors = new ProtocolExecutorManager(Runnable::run)) {
            new TtsModelService(new TestLlmSupport.FakeGameEnvironment(), config, executors);
            Thread.sleep(100L);
            assertTrue(Files.exists(partial));
        }
    }

    @Test
    void failedArchiveDownloadKeepsStagingForResume() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/model.tar.bz2", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            TestLlmSupport.FakeConfig config = new TestLlmSupport.FakeConfig(tempDir);
            TtsModelInfo info = new TtsModelInfo();
            info.name = "archive-recovery";
            info.engine = "vits";
            info.downloadUri = "http://localhost:" + server.getAddress().getPort() + "/model.tar.bz2";
            CountDownLatch failed = new CountDownLatch(1);

            try (ProtocolExecutorManager executors = new ProtocolExecutorManager(Runnable::run)) {
                TtsModelService service = new TtsModelService(new TestLlmSupport.FakeGameEnvironment(), config, executors);
                service.downloadModel(info, null, new TtsModelService.DownloadProgressCallback() {
                    @Override
                    public void onProgress(ModelDownloadProgress progress) {
                    }

                    @Override
                    public void onComplete() {
                    }

                    @Override
                    public void onError(String message) {
                        failed.countDown();
                    }
                });

                assertTrue(failed.await(10, TimeUnit.SECONDS));
                assertTrue(Files.isDirectory(config.getTtsBasePath()
                        .resolve("model")
                        .resolve("archive-recovery-staging")));
            }
        } finally {
            server.stop(0);
        }
    }
}
