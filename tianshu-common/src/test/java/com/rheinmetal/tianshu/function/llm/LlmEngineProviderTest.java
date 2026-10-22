package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.function.llm.service.EmbeddingLoadReport;
import com.rheinmetal.tianshu.function.llm.settings.LlmConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmEngineProviderTest {
    @TempDir
    Path tempDir;

    @Test
    void embeddingIsNotConfiguredBeforeTheEngineStarts() {
        TestLlmSupport.FakeGameEnvironment env = new TestLlmSupport.FakeGameEnvironment();
        LlmEngineProvider provider = new LlmEngineProvider(env, new TestLlmSupport.FakeConfig(tempDir));

        assertFalse(provider.isEmbeddingConfigured());
        assertNull(provider.embeddingService());
        assertTrue(env.warnings.isEmpty());
    }

    @Test
    void startWithoutSelectedEmbeddingModelKeepsVectorRagDisabled() {
        TestLlmSupport.FakeGameEnvironment env = new TestLlmSupport.FakeGameEnvironment();
        LlmEngineProvider provider = new LlmEngineProvider(env, new TestLlmSupport.FakeConfig(tempDir));

        assertFalse(provider.startEmbeddingOnly());
        assertFalse(provider.isEmbeddingConfigured());
        assertNull(provider.embeddingService());
        assertTrue(warned(env, "no embedding model is selected"), env.warnings.toString());
    }

    @Test
    void startReportsAMissingEmbeddingModelFileInsteadOfChatLoading() {
        TestLlmSupport.FakeGameEnvironment env = new TestLlmSupport.FakeGameEnvironment();
        LlmEngineProvider provider = new LlmEngineProvider(
                env,
                new TestLlmSupport.FakeConfig(tempDir).embeddingModelName("absent-embedding")
        );

        assertFalse(provider.startEmbeddingOnly());
        assertFalse(provider.isEmbeddingConfigured());
        assertTrue(warned(env, "the embedding model file does not exist"), env.warnings.toString());
    }

    @Test
    void startBindsAnEmbeddingEngineAndStopReleasesIt() throws Exception {
        TestLlmSupport.FakeGameEnvironment env = new TestLlmSupport.FakeGameEnvironment();
        Path modelFile = tempDir.resolve("llm").resolve("model").resolve("embed.gguf");
        Files.createDirectories(modelFile.getParent());
        Files.writeString(modelFile, "placeholder");
        // A queue-only executor keeps the model read from ever touching the placeholder file.
        LlmEngineProvider provider = new LlmEngineProvider(
                env,
                new TestLlmSupport.FakeConfig(tempDir).embeddingModelName("embed.gguf")
                        .embeddingGpuLayers(LlmConfiguration.EMBEDDING_GPU_LAYERS_ALL),
                task -> { }
        );

        assertTrue(provider.startEmbeddingOnly());
        assertTrue(provider.isEmbeddingConfigured());
        assertNotNull(provider.embeddingService());
        // The placeholder was never opened: binding is decided without reading the model.
        assertTrue(env.warnings.isEmpty(), env.warnings.toString());

        provider.stop();

        assertFalse(provider.isEmbeddingConfigured());
        assertNull(provider.embeddingService());
        assertTrue(env.warnings.isEmpty(), env.warnings.toString());
    }

    @Test
    void startQueuesTheModelReadAndReportsAModelThatCannotEmbed() throws Exception {
        TestLlmSupport.FakeGameEnvironment env = new TestLlmSupport.FakeGameEnvironment();
        Path modelFile = tempDir.resolve("llm").resolve("model").resolve("embed.gguf");
        Files.createDirectories(modelFile.getParent());
        Files.writeString(modelFile, "placeholder");
        List<Runnable> queued = new ArrayList<>();
        List<EmbeddingLoadReport> reports = new ArrayList<>();
        LlmEngineProvider provider = new LlmEngineProvider(
                env,
                new TestLlmSupport.FakeConfig(tempDir).embeddingModelName("embed.gguf")
                        .embeddingGpuLayers(LlmConfiguration.EMBEDDING_GPU_LAYERS_ALL),
                queued::add,
                reports::add
        );

        // Handing the engine to the model-load lane is all the caller does; queued is not ready.
        assertTrue(provider.startEmbeddingOnly());
        assertEquals(1, queued.size(), "the model read has to be queued, never run on the caller's thread");
        assertTrue(env.warnings.isEmpty(), env.warnings.toString());
        assertTrue(reports.isEmpty(), reports.toString());

        // The lane runs it: the placeholder is not a model, so the card attempt and the CPU retry both fail.
        queued.get(0).run();

        assertFalse(provider.isEmbeddingConfigured());
        assertNull(provider.embeddingService(), "a lane that cannot embed must not stay bound to RAG");
        assertEquals(1, reports.size(), reports.toString());
        assertEquals(EmbeddingLoadReport.Outcome.UNAVAILABLE, reports.get(0).outcome());
        assertFalse(reports.get(0).detail().isBlank(), "the failure has to name itself");
        assertTrue(warned(env, "could not run on the card"), env.warnings.toString());
        assertTrue(warned(env, "Failed to load the local embedding model embed.gguf"), env.warnings.toString());
        assertEquals(1L, warningsContaining(env, "Failed to load the local embedding model"),
                env.warnings.toString());

        List<String> warningsWhileLoaded = List.copyOf(env.warnings);
        provider.stop();

        assertNull(provider.embeddingService());
        assertEquals(warningsWhileLoaded, env.warnings, "releasing a dead engine must not report a new failure");
    }

    private static boolean warned(TestLlmSupport.FakeGameEnvironment env, String fragment) {
        return env.warnings.stream().anyMatch(warning -> warning.contains(fragment));
    }

    private static long warningsContaining(TestLlmSupport.FakeGameEnvironment env, String fragment) {
        return env.warnings.stream().filter(warning -> warning.contains(fragment)).count();
    }
}
