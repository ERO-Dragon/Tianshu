package com.rheinmetal.tianshu.function.weaving.service;

import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmRagStorageServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void storageSupportsVectorWritesAndBm25BeforeGenerationLoads() {
        LlmRagStorageService storage = storage(new EmbeddingRuntime());

        storage.registerLibrary("memory", "tianshu", "PRIVATE", List.of("ax"));
        assertTrue(storage.upsert("memory", "entry", "diamond pickaxe in ender chest", new float[]{1f, 0f}).success());
        assertTrue(storage.hasEntry("memory", "entry"));
        assertFalse(storage.searchEntries("memory", "where is the pickaxe", 4, 0.0f).isEmpty());
    }

    @Test
    void aWriteThatNeedsAVectorFailsUntilTheEmbeddingLaneIsBound() {
        EmbeddingRuntime embedding = new EmbeddingRuntime();
        LlmRagStorageService storage = storage(embedding);

        assertEquals("EMBEDDING_NOT_READY",
                storage.upsert("memory", "entry", "fact without a vector", null).errorCode());

        embedding.bind(new FixedEmbeddingService());
        assertTrue(storage.upsert("memory", "entry", "fact without a vector", null).success());
    }

    @Test
    void inlineSearchIsLexicalAndSideEffectFree() {
        LlmRagStorageService storage = storage(new EmbeddingRuntime());

        List<RagCacheManager.RagEntrySearchResult> hits = storage.searchInline(
                List.of("the diamond pickaxe is in the ender chest", "cow farm needs wheat"),
                "where is the diamond pickaxe",
                4,
                0.0f
        );

        assertFalse(hits.isEmpty());
        assertFalse(storage.hasCache(""), "inline search must not register a library");
    }

    private LlmRagStorageService storage(EmbeddingRuntime embedding) {
        return new LlmRagStorageService(
                new FakeGameEnvironment(),
                tempDir,
                "test",
                true,
                Runnable::run,
                RagPersistenceScheduler.immediate(),
                embedding
        );
    }

    private static final class FixedEmbeddingService implements EmbeddingService {
        @Override
        public float[] embed(String text) {
            return new float[]{1f, 0f};
        }

        @Override
        public float[][] embed(List<String> texts) {
            return texts.stream().map(ignored -> new float[]{1f, 0f}).toArray(float[][]::new);
        }

        @Override
        public int getEmbeddingDimension() {
            return 2;
        }
    }
}
