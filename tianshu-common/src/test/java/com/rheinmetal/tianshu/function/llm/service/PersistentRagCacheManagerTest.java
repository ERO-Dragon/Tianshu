package com.rheinmetal.tianshu.function.llm.service;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistentRagCacheManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void upsertSkipsDiskWriteWhenEntryIsUnchanged() throws Exception {
        CountingEmbeddingService embeddings = new CountingEmbeddingService();
        PersistentRagCacheManager cache = new PersistentRagCacheManager(new FakeGameEnvironment(), embeddings, tempDir, "test");

        cache.upsert("memory", "entry-a", "same memory", null);

        Path vectorsFile = tempDir.resolve("memory.bin");
        assertTrue(Files.isRegularFile(vectorsFile));
        FileTime firstModified = Files.getLastModifiedTime(vectorsFile);
        long firstSize = Files.size(vectorsFile);

        Thread.sleep(25L);
        cache.upsert("memory", "entry-a", "same memory", null);

        assertEquals(1, embeddings.singleCalls);
        assertEquals(firstModified, Files.getLastModifiedTime(vectorsFile));
        assertEquals(firstSize, Files.size(vectorsFile));
    }

    @Test
    void upsertKeepsMultipleEntriesForSameUid() {
        CountingEmbeddingService embeddings = new CountingEmbeddingService();
        PersistentRagCacheManager cache = new PersistentRagCacheManager(new FakeGameEnvironment(), embeddings, tempDir, "test");

        cache.upsert("memory", "old", "old memory", null);
        cache.upsert("memory", "new", "new memory", null);

        assertEquals(2, embeddings.singleCalls);
        assertEquals(2, cache.getStats().getTotalChunks());
        assertTrue(cache.search("memory", "anything", 10, 0.1f).stream()
                .anyMatch(result -> "old memory".equals(result.getContent())));
        assertTrue(cache.search("memory", "anything", 10, 0.1f).stream()
                .anyMatch(result -> "new memory".equals(result.getContent())));
    }

    @Test
    void upsertPatchAndSearchOperateOnEntryIds() {
        CountingEmbeddingService embeddings = new CountingEmbeddingService();
        PersistentRagCacheManager cache = new PersistentRagCacheManager(new FakeGameEnvironment(), embeddings, tempDir, "test");

        cache.upsert("route", "cluster-a", "", new float[]{1f, 0f});
        cache.patch("route", "cluster-a", "cluster summary", null, true, false);
        List<RagCacheManager.RagEntrySearchResult> results = cache.searchEntries("route", "anything", 5, 0.1f);

        assertEquals(1, results.size());
        assertEquals("cluster-a", results.get(0).entryId());
        assertEquals("cluster summary", results.get(0).content());
    }

    @Test
    void patchContentDoesNotReplaceExistingVector() {
        CountingEmbeddingService embeddings = new CountingEmbeddingService();
        PersistentRagCacheManager cache = new PersistentRagCacheManager(new FakeGameEnvironment(), embeddings, tempDir, "test");

        cache.upsert("route", "cluster-a", "", new float[]{0f, 1f});
        cache.patch("route", "cluster-a", "summary text", null, true, false);
        cache.upsert("route", "cluster-b", "embedded text", null);

        List<RagCacheManager.RagEntrySearchResult> results = cache.searchEntries("route", "query", 1, 0.1f);

        assertEquals("cluster-b", results.get(0).entryId());
    }

    @Test
    void bm25SearchStillWorksWhenQueryEmbeddingFails() {
        CountingEmbeddingService embeddings = new CountingEmbeddingService();
        PersistentRagCacheManager cache = new PersistentRagCacheManager(new FakeGameEnvironment(), embeddings, tempDir, "test");

        cache.upsert("memory", "target", "diamond pickaxe in ender chest", new float[]{0f, 1f});
        cache.upsert("memory", "other", "player went fishing", new float[]{0f, 1f});

        embeddings.failSingleEmbed = true;
        List<RagCacheManager.RagEntrySearchResult> results = cache.searchEntries("memory", "where is the diamond pickaxe", 1, 0.7f);

        assertEquals(1, results.size());
        assertEquals("target", results.get(0).entryId());
    }

    @Test
    void upsertBatchesDiskWritesUntilScheduledFlushRuns() {
        CountingEmbeddingService embeddings = new CountingEmbeddingService();
        ManualRagPersistenceScheduler scheduler = new ManualRagPersistenceScheduler();
        PersistentRagCacheManager cache = new PersistentRagCacheManager(
                new FakeGameEnvironment(),
                embeddings,
                tempDir,
                "test",
                Runnable::run,
                scheduler
        );

        cache.upsert("memory", "entry-a", "first memory", null);
        cache.upsert("memory", "entry-b", "second memory", null);

        assertEquals(1, scheduler.scheduledCount);
        assertEquals(false, Files.exists(tempDir.resolve("memory.bin")));

        scheduler.runScheduled();

        assertEquals(true, Files.isRegularFile(tempDir.resolve("memory.bin")));
        PersistentRagCacheManager reloaded = new PersistentRagCacheManager(new FakeGameEnvironment(), embeddings, tempDir, "test");
        assertEquals(true, reloaded.hasEntry("memory", "entry-a"));
        assertEquals(true, reloaded.hasEntry("memory", "entry-b"));
    }

    private static final class CountingEmbeddingService implements EmbeddingService {
        private int singleCalls;
        private int batchCalls;
        private final java.util.ArrayList<Integer> batchSizes = new java.util.ArrayList<>();
        private boolean failSingleEmbed;

        @Override
        public float[] embed(String text) {
            singleCalls++;
            if (failSingleEmbed) {
                throw new IllegalStateException("embedding unavailable");
            }
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

    private static final class ManualRagPersistenceScheduler implements RagPersistenceScheduler {
        private final List<Runnable> scheduled = new ArrayList<>();
        private int scheduledCount;

        @Override
        public void schedule(Runnable task, Duration delay) {
            scheduledCount++;
            scheduled.add(task);
        }

        void runScheduled() {
            List<Runnable> tasks = new ArrayList<>(scheduled);
            scheduled.clear();
            tasks.forEach(Runnable::run);
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
