package com.rheinmetal.tianshu.function.weaving.service;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistentRagCacheManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void upsertSkipsDiskWriteWhenEntryIsUnchanged() throws Exception {
        CountingEmbeddingService embeddings = new CountingEmbeddingService();
        PersistentRagCacheManager cache = new PersistentRagCacheManager(new FakeGameEnvironment(), embeddings, tempDir, "test");

        cache.upsert("memory", "entry-a", "same memory", null);

        Path vectorsFile = onlyCacheFile();
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

    /**
     * Two different libraries used to collapse onto one file, because the readable part of the name is
     * sanitized: "mod:a" and "mod_a" both became "mod_a.bin" and the second library silently overwrote
     * the first one's entries on disk.
     */
    @Test
    void librariesWhoseNamesSanitizeAlikeKeepSeparateStores() throws Exception {
        CountingEmbeddingService embeddings = new CountingEmbeddingService();
        PersistentRagCacheManager cache = new PersistentRagCacheManager(new FakeGameEnvironment(), embeddings, tempDir, "test");

        cache.upsert("mod:a", "entry", "alpha memory", null);
        cache.upsert("mod_a", "entry", "beta memory", null);
        cache.flush();

        assertEquals(2, cache.getStats().getUidCount());
        assertEquals(2, cacheFiles().count(), "each library keeps its own file");
        assertTrue(cache.search("mod:a", "alpha", 5, 0.1f).stream()
                .anyMatch(result -> "alpha memory".equals(result.getContent())));
        assertFalse(cache.search("mod:a", "alpha", 5, 0.1f).stream()
                .anyMatch(result -> "beta memory".equals(result.getContent())));
    }

    /**
     * The pre-digest file name is read once and rewritten under the current one, so an upgrade keeps the
     * cached vectors instead of rebuilding them on the next launch.
     */
    @Test
    void aLegacyNamedStoreIsReadAndMigratedToTheCurrentName() throws Exception {
        CountingEmbeddingService embeddings = new CountingEmbeddingService();
        PersistentRagCacheManager first = new PersistentRagCacheManager(new FakeGameEnvironment(), embeddings, tempDir, "test");
        first.upsert("legacy:uid", "entry", "old memory", null);
        first.flush();

        Path legacyFile = tempDir.resolve("legacy_uid.bin");
        Files.move(onlyCacheFile(), legacyFile);

        PersistentRagCacheManager reopened = new PersistentRagCacheManager(new FakeGameEnvironment(), embeddings, tempDir, "test");

        assertTrue(reopened.hasCache("legacy:uid"), "the legacy file must still be readable");
        assertFalse(Files.exists(legacyFile), "the migration must remove the legacy file once it is rewritten");
        assertTrue(Files.isRegularFile(onlyCacheFile()), "the store must live under the current name");
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
    void upsertBatchesDiskWritesUntilScheduledFlushRuns() throws Exception {
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
        assertFalse(hasCacheFile(), "nothing is written before the scheduled flush runs");

        scheduler.runScheduled();

        assertTrue(hasCacheFile(), "the scheduled flush writes the file");
        PersistentRagCacheManager reloaded = new PersistentRagCacheManager(new FakeGameEnvironment(), embeddings, tempDir, "test");
        assertEquals(true, reloaded.hasEntry("memory", "entry-a"));
        assertEquals(true, reloaded.hasEntry("memory", "entry-b"));
    }

    /** The store files of this directory; the name is an implementation detail the tests must not pin. */
    private Stream<Path> cacheFiles() throws IOException {
        return Files.list(tempDir)
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".bin"));
    }

    private Path onlyCacheFile() throws IOException {
        try (Stream<Path> files = cacheFiles()) {
            return files.findFirst().orElseThrow(() -> new IOException("no cache file was written"));
        }
    }

    private boolean hasCacheFile() throws IOException {
        try (Stream<Path> files = cacheFiles()) {
            return files.findAny().isPresent();
        }
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
        @Override public void executeOnMainThread(Runnable task) { task.run(); }
        @Override public Path getGameDirectory() { return Path.of("."); }
        @Override public boolean isClientSide() { return true; }
        @Override public void openFolder(Path dir) {}
        @Override public void info(String msg) {}
        @Override public void warn(String msg) {}
        @Override public void error(String msg, Throwable t) {}
        @Override public com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink diagnostics() { return com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink.NOOP; }
    }
}
