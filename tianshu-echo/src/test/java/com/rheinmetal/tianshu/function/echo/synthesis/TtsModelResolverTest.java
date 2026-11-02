package com.rheinmetal.tianshu.function.echo.synthesis;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.echo.TtsModelService;
import com.rheinmetal.tianshu.function.echo.model.TtsModelInfo;
import com.rheinmetal.tianshu.function.echo.settings.EchoConfiguration;
import com.rheinmetal.tianshu.protocol.runtime.ModuleExecutionAccess;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutorManager;
import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import com.rheinmetal.tianshu.testkit.TestLlmSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runtime resolution has to agree with the settings page: a directory that exists but does not carry
 * what its catalog entry declares is not an installed model.
 */
class TtsModelResolverTest {
    private static final String MODEL_NAME = "resolver-test-model";
    private static final String LEGACY_MODEL_NAME = "resolver-legacy-model";

    @TempDir
    Path tempDir;

    @Test
    void directoryWithoutTheDeclaredFilesDoesNotResolve() throws Exception {
        try (ProtocolExecutorManager executors = new ProtocolExecutorManager(Runnable::run)) {
            Fixture fixture = fixture(executors, modelInfo());
            // The directory exists, so the old directory-only check would have resolved it, but none
            // of the files the catalog declares are on disk.
            Files.createDirectories(fixture.modelDirectory(MODEL_NAME));

            TtsModelResolver resolver = new TtsModelResolver(fixture.env(), fixture.service());

            assertTrue(resolver.resolve(MODEL_NAME).isEmpty());
            assertTrue(fixture.env().warnings.stream()
                            .anyMatch(warning -> warning.startsWith("tts.model.directory_incomplete")),
                    "the rejection has to be reported as an incomplete model, not silently dropped");
        }
    }

    @Test
    void completeDirectoryResolvesSoTheCheckIsTheOnlyDifference() throws Exception {
        try (ProtocolExecutorManager executors = new ProtocolExecutorManager(Runnable::run)) {
            TtsModelInfo info = modelInfo();
            Fixture fixture = fixture(executors, info);
            Path modelDir = fixture.modelDirectory(MODEL_NAME);
            Files.createDirectories(modelDir);
            write(modelDir, "model.onnx");
            write(modelDir, "lexicon.txt");
            write(modelDir, "tokens.txt");

            TtsModelResolver resolver = new TtsModelResolver(fixture.env(), fixture.service());
            Optional<TtsResolvedModel> resolved = resolver.resolve(MODEL_NAME);

            assertTrue(resolved.isPresent(), "a complete directory must still resolve");
            assertEquals(TtsBackendType.SHERPA, resolved.get().backendType());
            assertSame(info, resolved.get().modelInfo());
        }
    }

    @Test
    void directoryTheCatalogDoesNotKnowKeepsResolving() throws Exception {
        try (ProtocolExecutorManager executors = new ProtocolExecutorManager(Runnable::run)) {
            // A legacy/custom directory has no catalog entry, so there is nothing to check it against;
            // the existing directory-only behaviour is deliberately unchanged for that case.
            Fixture fixture = fixture(executors, modelInfo());
            Files.createDirectories(fixture.modelDirectory(LEGACY_MODEL_NAME));

            TtsModelResolver resolver = new TtsModelResolver(fixture.env(), fixture.service());
            Optional<TtsResolvedModel> resolved = resolver.resolve(LEGACY_MODEL_NAME);

            assertTrue(resolved.isPresent());
            assertNull(resolved.get().modelInfo());
        }
    }

    private Fixture fixture(ModuleExecutionAccess executors, TtsModelInfo info) {
        FakeGameEnvironment env = new FakeGameEnvironment();
        EchoConfiguration config = new TestLlmSupport.FakeConfig(tempDir);
        TtsModelService service = new CatalogBackedTtsModelService(env, config, executors, List.of(info));
        return new Fixture(env, service, config.getTtsBasePath());
    }

    private static void write(Path directory, String fileName) throws Exception {
        Files.writeString(directory.resolve(fileName), "test-content", StandardCharsets.UTF_8);
    }

    private static TtsModelInfo modelInfo() {
        TtsModelInfo info = new TtsModelInfo();
        info.name = MODEL_NAME;
        info.id = "test/resolver";
        info.engine = TtsModelInfo.ENGINE_VITS;
        info.modelFiles = List.of("model.onnx");
        info.lexiconFiles = List.of("lexicon.txt");
        info.ruleFsts = List.of();
        info.voicesFile = "";
        info.dataDir = "";
        return info;
    }

    private record Fixture(FakeGameEnvironment env, TtsModelService service, Path ttsBasePath) {
        private Path modelDirectory(String name) {
            return ttsBasePath.resolve("model").resolve(name);
        }
    }

    /** Keeps the catalog deterministic: the real catalog ships whatever the module currently declares. */
    private static final class CatalogBackedTtsModelService extends TtsModelService {
        private final List<TtsModelInfo> models;

        private CatalogBackedTtsModelService(
                IGameEnvironment env,
                EchoConfiguration config,
                ModuleExecutionAccess executors,
                List<TtsModelInfo> models
        ) {
            super(env, config, executors);
            this.models = models;
        }

        @Override
        public List<TtsModelInfo> catalog() {
            return models;
        }
    }
}
