package com.rheinmetal.tianshu.function.llm.service;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistentRagCacheManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void indexSkipsEmbeddingAndDiskWriteWhenTextsAlreadyCached() throws Exception {
        CountingEmbeddingService embeddings = new CountingEmbeddingService();
        PersistentRagCacheManager cache = new PersistentRagCacheManager(new FakeGameEnvironment(), embeddings, tempDir, "test");

        cache.index("memory", List.of("same memory"));

        Path vectorsFile = tempDir.resolve("memory.bin");
        assertTrue(Files.isRegularFile(vectorsFile));
        FileTime firstModified = Files.getLastModifiedTime(vectorsFile);
        long firstSize = Files.size(vectorsFile);

        Thread.sleep(25L);
        cache.index("memory", List.of("same memory"));

        assertEquals(1, embeddings.batchCalls);
        assertEquals(firstModified, Files.getLastModifiedTime(vectorsFile));
        assertEquals(firstSize, Files.size(vectorsFile));
    }

    @Test
    void indexKeepsIncrementalAppendForSameUid() {
        CountingEmbeddingService embeddings = new CountingEmbeddingService();
        PersistentRagCacheManager cache = new PersistentRagCacheManager(new FakeGameEnvironment(), embeddings, tempDir, "test");

        cache.index("memory", List.of("old memory"));
        cache.index("memory", List.of("old memory", "new memory"));

        assertEquals(2, embeddings.batchCalls);
        assertEquals(List.of(1, 1), embeddings.batchSizes);
        assertEquals(2, cache.getStats().getTotalChunks());
        assertTrue(cache.search("memory", "anything", 10, 0.1f).stream()
                .anyMatch(result -> "old memory".equals(result.getContent())));
        assertTrue(cache.search("memory", "anything", 10, 0.1f).stream()
                .anyMatch(result -> "new memory".equals(result.getContent())));
    }

    private static final class CountingEmbeddingService implements EmbeddingService {
        private int batchCalls;
        private final java.util.ArrayList<Integer> batchSizes = new java.util.ArrayList<>();

        @Override
        public float[] embed(String text) {
            return new float[]{1f, 0f};
        }

        @Override
        public float[][] embed(List<String> texts) {
            batchCalls++;
            batchSizes.add(texts.size());
            float[][] result = new float[texts.size()][];
            for (int i = 0; i < texts.size(); i++) {
                result[i] = new float[]{1f, 0f};
            }
            return result;
        }

        @Override
        public int getEmbeddingDimension() {
            return 2;
        }
    }

    private static final class FakeGameEnvironment implements IGameEnvironment {
        @Override public void displayMessageToPlayer(String message) {}
        @Override public void executeOnMainThread(Runnable task) { task.run(); }
        @Override public Path getGameDirectory() { return Path.of("."); }
        @Override public boolean isClientSide() { return true; }
        @Override public void openFolder(Path dir) {}
        @Override public void info(String msg) {}
        @Override public void warn(String msg) {}
        @Override public void error(String msg, Throwable t) {}
    }
}
