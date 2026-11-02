package com.rheinmetal.tianshu.function.echo;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.echo.download.TtsModelDownloadCoordinator;
import com.rheinmetal.tianshu.function.echo.model.TtsModelInfo;
import com.rheinmetal.tianshu.function.echo.settings.EchoConfiguration;
import com.rheinmetal.tianshu.model.HuggingFaceDownloader;
import com.rheinmetal.tianshu.model.ModelAvailabilitySnapshot;
import com.rheinmetal.tianshu.model.ModelDownloadHttpClient;
import com.rheinmetal.tianshu.model.ModelDownloadProgress;
import com.rheinmetal.tianshu.model.ModelDownloadSourcePolicy;
import com.rheinmetal.tianshu.model.lifecycle.ModelDownloadCallback;
import com.rheinmetal.tianshu.protocol.runtime.ModuleExecutionAccess;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutorManager;
import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import com.rheinmetal.tianshu.testkit.ModelDownloadTestServer;
import com.rheinmetal.tianshu.testkit.TestLlmSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Downloads a model from a local Hugging Face stand-in and deletes it again.
 *
 * <p>The point is the hand-off between the module service and the transport: what ends up in the model
 * directory, what the settings page is told, and how a download and a deletion refuse to overlap. The
 * service's catalog is replaced with the single synthetic entry, because availability is catalog driven
 * and a model the catalog does not know can never be reported installed.</p>
 */
class TtsModelDownloadEndToEndTest {
    private static final String REPO_ID = "test/repo";
    private static final String MODEL_NAME = "download-e2e-model";
    private static final long AWAIT_SECONDS = 15L;

    @TempDir
    Path tempDir;

    @Test
    void downloadInstallsTheModelWithoutWritingAModelSettingsFile() throws Exception {
        byte[] modelBytes = "onnx-model-content".getBytes(StandardCharsets.UTF_8);
        byte[] lexiconBytes = "lexicon-entry 0\n".getBytes(StandardCharsets.UTF_8);
        byte[] tokensBytes = "<unk> 0\n".getBytes(StandardCharsets.UTF_8);

        try (ModelDownloadTestServer server = new ModelDownloadTestServer();
             ProtocolExecutorManager executors = new ProtocolExecutorManager(Runnable::run)) {
            enqueueRepository(server, modelBytes, lexiconBytes, tokensBytes);
            FakeGameEnvironment env = new FakeGameEnvironment();
            TestLlmSupport.FakeConfig config = new TestLlmSupport.FakeConfig(tempDir);
            TtsModelInfo info = vitsModel(modelBytes.length + lexiconBytes.length + tokensBytes.length);
            Path modelDir = config.getTtsBasePath().resolve("model").resolve(MODEL_NAME);

            TtsModelService service = new CatalogBackedTtsModelService(env, config, executors,
                    new TtsModelDownloadCoordinator(env, () -> newDownloader(env, server)), info);

            CountDownLatch downloadDone = new CountDownLatch(1);
            AtomicReference<String> downloadError = new AtomicReference<>();
            service.downloadModel(info, null, downloadCallback(downloadDone, downloadError));

            assertTrue(downloadDone.await(AWAIT_SECONDS, TimeUnit.SECONDS), "the download never reported an outcome");
            assertNull(downloadError.get(), "the download failed: " + downloadError.get());

            assertTrue(Files.isRegularFile(modelDir.resolve("model.onnx")), "the declared model graph is missing");
            assertTrue(Files.isRegularFile(modelDir.resolve("lexicon.txt")), "the declared lexicon is missing");
            assertTrue(Files.isRegularFile(modelDir.resolve("tokens.txt")), "the tokens file is missing");
            assertFalse(Files.exists(modelDir.resolve("model-settings.json")),
                    "a download must not persist a settings file that would override the catalog default voice");

            ModelAvailabilitySnapshot.Entry entry = service.modelAvailability().entry(MODEL_NAME);
            assertNotNull(entry, "the downloaded model is missing from the availability snapshot");
            assertTrue(entry.installed(), "the downloaded model is not reported as installed");

            AtomicBoolean deleted = new AtomicBoolean(false);
            CountDownLatch deleteDone = new CountDownLatch(1);
            service.deleteModelAsync(info, result -> {
                deleted.set(result);
                deleteDone.countDown();
            });

            assertTrue(deleteDone.await(AWAIT_SECONDS, TimeUnit.SECONDS), "the deletion never reported an outcome");
            assertTrue(deleted.get(), "a complete model directory must be deletable");
            assertFalse(Files.exists(modelDir), "the deleted model directory is still on disk");
            assertFalse(service.hasModelDirectory(info), "a deleted model still reports a model directory");
        }
    }

    @Test
    void deletionIsRefusedWhileADownloadIsRunning() throws Exception {
        byte[] modelBytes = "onnx-model-content".getBytes(StandardCharsets.UTF_8);
        byte[] lexiconBytes = "lexicon-entry 0\n".getBytes(StandardCharsets.UTF_8);
        byte[] tokensBytes = "<unk> 0\n".getBytes(StandardCharsets.UTF_8);

        try (ModelDownloadTestServer server = new ModelDownloadTestServer();
             ProtocolExecutorManager executors = new ProtocolExecutorManager(Runnable::run)) {
            enqueueRepository(server, modelBytes, lexiconBytes, tokensBytes);
            FakeGameEnvironment env = new FakeGameEnvironment();
            TestLlmSupport.FakeConfig config = new TestLlmSupport.FakeConfig(tempDir);
            TtsModelInfo info = vitsModel(modelBytes.length + lexiconBytes.length + tokensBytes.length);
            Path modelDir = config.getTtsBasePath().resolve("model").resolve(MODEL_NAME);
            Files.createDirectories(modelDir);

            CountDownLatch transferStarted = new CountDownLatch(1);
            CountDownLatch releaseTransfer = new CountDownLatch(1);
            TtsModelService service = new CatalogBackedTtsModelService(env, config, executors,
                    new TtsModelDownloadCoordinator(env,
                            () -> blockingDownloader(env, server, transferStarted, releaseTransfer)), info);

            CountDownLatch downloadDone = new CountDownLatch(1);
            AtomicReference<String> downloadError = new AtomicReference<>();
            service.downloadModel(info, null, downloadCallback(downloadDone, downloadError));
            assertTrue(transferStarted.await(AWAIT_SECONDS, TimeUnit.SECONDS), "the transfer never started");

            AtomicBoolean deleted = new AtomicBoolean(true);
            CountDownLatch deleteDone = new CountDownLatch(1);
            service.deleteModelAsync(info, result -> {
                deleted.set(result);
                deleteDone.countDown();
            });

            assertTrue(deleteDone.await(AWAIT_SECONDS, TimeUnit.SECONDS), "the deletion never reported an outcome");
            assertFalse(deleted.get(), "a running download must refuse the deletion");
            assertTrue(Files.isDirectory(modelDir), "the refused deletion must leave the model directory alone");

            releaseTransfer.countDown();
            assertTrue(downloadDone.await(AWAIT_SECONDS, TimeUnit.SECONDS), "the download must still be able to finish");
            assertNull(downloadError.get(), "the download failed: " + downloadError.get());
        }
    }

    /**
     * A transfer that does not deliver every declared file is a retry, and the player is told so instead of
     * seeing the generic failure. The staging directory survives, so the retry resumes.
     */
    @Test
    void anIncompleteTransferIsReportedAsRetryableAndKeepsStaging() throws Exception {
        byte[] modelBytes = "onnx-model-content".getBytes(StandardCharsets.UTF_8);
        byte[] lexiconBytes = "lexicon-entry 0\n".getBytes(StandardCharsets.UTF_8);

        try (ModelDownloadTestServer server = new ModelDownloadTestServer();
             ProtocolExecutorManager executors = new ProtocolExecutorManager(Runnable::run)) {
            // The repository does not have the tokens file the model needs, so the transfer "succeeds"
            // file by file and still cannot produce a usable model.
            server.enqueue("/api/models/" + REPO_ID + "/tree/main", ModelDownloadTestServer.text(200, """
                    [{"type":"file","path":"model.onnx","size":%d},
                     {"type":"file","path":"lexicon.txt","size":%d}]
                    """.formatted(modelBytes.length, lexiconBytes.length)));
            server.enqueue("/" + REPO_ID + "/resolve/main/model.onnx", ModelDownloadTestServer.bytes(200, modelBytes));
            server.enqueue("/" + REPO_ID + "/resolve/main/lexicon.txt", ModelDownloadTestServer.bytes(200, lexiconBytes));

            FakeGameEnvironment env = new FakeGameEnvironment();
            TestLlmSupport.FakeConfig config = new TestLlmSupport.FakeConfig(tempDir);
            TtsModelInfo info = vitsModel(modelBytes.length + lexiconBytes.length);
            Path modelDir = config.getTtsBasePath().resolve("model").resolve(MODEL_NAME);
            TtsModelService service = new CatalogBackedTtsModelService(env, config, executors,
                    new TtsModelDownloadCoordinator(env, () -> newDownloader(env, server)), info);

            CountDownLatch downloadDone = new CountDownLatch(1);
            AtomicReference<String> downloadError = new AtomicReference<>();
            service.downloadModel(info, null, downloadCallback(downloadDone, downloadError));

            assertTrue(downloadDone.await(AWAIT_SECONDS, TimeUnit.SECONDS), "the download never reported an outcome");
            assertEquals("tianshu.gui.tts.status.download_incomplete", downloadError.get(),
                    "an incomplete transfer must name its cause so the player knows a retry is all it needs");
            assertFalse(Files.isDirectory(modelDir), "an incomplete transfer must not be installed");
            assertTrue(Files.isDirectory(config.getTtsBasePath().resolve("model").resolve(MODEL_NAME + "-staging")),
                    "the staging directory has to survive so the retry resumes instead of starting over");
        }
    }

    private static void enqueueRepository(
            ModelDownloadTestServer server,
            byte[] modelBytes,
            byte[] lexiconBytes,
            byte[] tokensBytes
    ) {
        server.enqueue("/api/models/" + REPO_ID + "/tree/main", ModelDownloadTestServer.text(200, """
                [{"type":"file","path":"model.onnx","size":%d},
                 {"type":"file","path":"lexicon.txt","size":%d},
                 {"type":"file","path":"tokens.txt","size":%d}]
                """.formatted(modelBytes.length, lexiconBytes.length, tokensBytes.length)));
        server.enqueue("/" + REPO_ID + "/resolve/main/model.onnx", ModelDownloadTestServer.bytes(200, modelBytes));
        server.enqueue("/" + REPO_ID + "/resolve/main/lexicon.txt", ModelDownloadTestServer.bytes(200, lexiconBytes));
        server.enqueue("/" + REPO_ID + "/resolve/main/tokens.txt", ModelDownloadTestServer.bytes(200, tokensBytes));
    }

    private static TtsModelInfo vitsModel(long size) {
        TtsModelInfo info = new TtsModelInfo();
        info.name = MODEL_NAME;
        info.engine = TtsModelInfo.ENGINE_VITS;
        info.id = REPO_ID;
        info.modelFiles = List.of("model.onnx");
        info.lexiconFiles = List.of("lexicon.txt");
        info.ruleFsts = List.of();
        info.voicesFile = "";
        info.dataDir = "";
        info.size = size;
        return info;
    }

    private static HuggingFaceDownloader newDownloader(IGameEnvironment env, ModelDownloadTestServer server) {
        return new HuggingFaceDownloader(
                env,
                new ModelDownloadSourcePolicy(server.baseUrl(), server.baseUrl()),
                new ModelDownloadHttpClient(env),
                server::baseUrl
        );
    }

    /** Holds the transfer on the IO lane open until the test says so, so the overlap is deterministic. */
    private static HuggingFaceDownloader blockingDownloader(
            IGameEnvironment env,
            ModelDownloadTestServer server,
            CountDownLatch transferStarted,
            CountDownLatch releaseTransfer
    ) {
        return new HuggingFaceDownloader(
                env,
                new ModelDownloadSourcePolicy(server.baseUrl(), server.baseUrl()),
                new ModelDownloadHttpClient(env),
                server::baseUrl
        ) {
            @Override
            public void downloadModelFiles(
                    String repoId,
                    Path targetDir,
                    String revision,
                    boolean skipExisting,
                    int maxRetries,
                    HuggingFaceDownloader.DownloadControl control,
                    HuggingFaceDownloader.DownloadProgressListener progress
            ) throws Exception {
                transferStarted.countDown();
                if (!releaseTransfer.await(AWAIT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the test never released the transfer");
                }
                super.downloadModelFiles(repoId, targetDir, revision, skipExisting, maxRetries, control, progress);
            }
        };
    }

    private static ModelDownloadCallback downloadCallback(CountDownLatch done, AtomicReference<String> error) {
        return new ModelDownloadCallback() {
            @Override
            public void onProgress(ModelDownloadProgress progress) {
            }

            @Override
            public void onComplete() {
                done.countDown();
            }

            @Override
            public void onError(String message) {
                error.set(message);
                done.countDown();
            }

            @Override
            public void onCancelled() {
                done.countDown();
            }
        };
    }

    private static final class CatalogBackedTtsModelService extends TtsModelService {
        private final List<TtsModelInfo> models;

        private CatalogBackedTtsModelService(
                IGameEnvironment env,
                EchoConfiguration config,
                ModuleExecutionAccess executors,
                TtsModelDownloadCoordinator coordinator,
                TtsModelInfo model
        ) {
            super(env, config, executors, ignored -> {
            }, coordinator);
            this.models = List.of(model);
        }

        @Override
        public List<TtsModelInfo> catalog() {
            return models;
        }
    }
}
