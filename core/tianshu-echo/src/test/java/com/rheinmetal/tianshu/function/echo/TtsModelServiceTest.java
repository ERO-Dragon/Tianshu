package com.rheinmetal.tianshu.function.echo;

import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import com.rheinmetal.tianshu.testkit.TestLlmSupport;
import com.rheinmetal.tianshu.model.ModelDownloadProgress;
import com.rheinmetal.tianshu.model.lifecycle.ModelDownloadCallback;
import com.rheinmetal.tianshu.function.echo.model.TtsModelInfo;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsModelServiceTest {
    @TempDir
    Path tempDir;

    /**
     * A staging directory whose model is already installed is a leftover from a commit that succeeded: it can
     * never be resumed, and for MOSS it is most of a gigabyte sitting there for good.
     */
    @Test
    void startupDropsStagingOfAnInstalledModel() throws Exception {
        TestLlmSupport.FakeConfig config = new TestLlmSupport.FakeConfig(tempDir);
        TtsModelInfo info = installedModel();
        Path modelDir = config.getTtsBasePath().resolve("model").resolve(info.name);
        writeCompleteModel(modelDir);
        Path staging = config.getTtsBasePath().resolve("model").resolve(info.name + "-staging");
        Files.createDirectories(staging);
        Files.writeString(staging.resolve("leftover.bin"), "leftover", StandardCharsets.UTF_8);

        try (ProtocolExecutorManager executors = new ProtocolExecutorManager(Runnable::run)) {
            new CatalogBackedService(new FakeGameEnvironment(), config, executors, info);
            Thread.sleep(100L);
            assertFalse(Files.exists(staging), "staging of an installed model is dead weight");
            assertTrue(Files.isDirectory(modelDir), "the installed model must not be touched");
        }
    }

    @Test
    void startupDropsAnEmptyStagingDirectoryButKeepsOneThatCanBeResumed() throws Exception {
        TestLlmSupport.FakeConfig config = new TestLlmSupport.FakeConfig(tempDir);
        TtsModelInfo info = installedModel();
        Path base = config.getTtsBasePath().resolve("model");
        Path emptyStaging = base.resolve(info.name + "-staging");
        Files.createDirectories(emptyStaging);
        Path resumable = base.resolve("other-staging");
        Files.createDirectories(resumable);
        Files.writeString(resumable.resolve("part.onnx.downloading"), "partial", StandardCharsets.UTF_8);

        try (ProtocolExecutorManager executors = new ProtocolExecutorManager(Runnable::run)) {
            new CatalogBackedService(new FakeGameEnvironment(), config, executors, info);
            Thread.sleep(100L);
            assertFalse(Files.exists(emptyStaging), "an empty staging directory has nothing to resume");
            assertTrue(Files.exists(resumable.resolve("part.onnx.downloading")),
                    "a transfer that can still continue must survive startup");
        }
    }

    private static TtsModelInfo installedModel() {
        TtsModelInfo info = new TtsModelInfo();
        info.name = "cleanup-model";
        info.engine = "vits";
        info.modelFiles = java.util.List.of("model.onnx");
        info.lexiconFiles = java.util.List.of("lexicon.txt");
        info.ruleFsts = java.util.List.of();
        info.voicesFile = "";
        info.dataDir = "";
        return info;
    }

    private static void writeCompleteModel(Path modelDir) throws Exception {
        Files.createDirectories(modelDir);
        Files.writeString(modelDir.resolve("model.onnx"), "onnx", StandardCharsets.UTF_8);
        Files.writeString(modelDir.resolve("lexicon.txt"), "lexicon", StandardCharsets.UTF_8);
        Files.writeString(modelDir.resolve("tokens.txt"), "tokens", StandardCharsets.UTF_8);
    }

    /** Only the catalog is replaced: the cleanup under test is the production one. */
    private static final class CatalogBackedService extends TtsModelService {
        private final java.util.List<TtsModelInfo> models;

        private CatalogBackedService(
                com.rheinmetal.tianshu.api.IGameEnvironment env,
                TestLlmSupport.FakeConfig config,
                ProtocolExecutorManager executors,
                TtsModelInfo model
        ) {
            super(env, config, executors);
            this.models = java.util.List.of(model);
        }

        @Override
        public java.util.List<TtsModelInfo> catalog() {
            return models;
        }
    }

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
            new TtsModelService(new FakeGameEnvironment(), config, executors);
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
                TtsModelService service = new TtsModelService(new FakeGameEnvironment(), config, executors);
                service.downloadModel(info, null, new ModelDownloadCallback() {
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
