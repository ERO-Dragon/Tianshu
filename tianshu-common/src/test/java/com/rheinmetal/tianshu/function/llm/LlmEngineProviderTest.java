package com.rheinmetal.tianshu.function.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

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
                new TestLlmSupport.FakeConfig(tempDir).embeddingModelName("embed.gguf"),
                task -> { }
        );

        assertTrue(provider.startEmbeddingOnly());
        assertTrue(provider.isEmbeddingConfigured());
        assertNotNull(provider.embeddingService());

        provider.stop();

        assertFalse(provider.isEmbeddingConfigured());
        assertNull(provider.embeddingService());
        assertTrue(env.warnings.isEmpty(), env.warnings.toString());
    }

    private static boolean warned(TestLlmSupport.FakeGameEnvironment env, String fragment) {
        return env.warnings.stream().anyMatch(warning -> warning.contains(fragment));
    }
}
