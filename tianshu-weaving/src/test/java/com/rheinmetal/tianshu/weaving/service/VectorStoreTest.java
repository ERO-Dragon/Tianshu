package com.rheinmetal.tianshu.weaving.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The store's lookup contract, and the bulk-import shape that contract exists for.
 *
 * <p>Every write, patch and read is keyed by entry id, so the store keeps an index by id instead of
 * scanning. The bulk case is the one that made the difference visible: a library import writes every
 * entry it holds, and a scan per write turned that import quadratic in the library size.</p>
 */
class VectorStoreTest {

    private static float[] vector(float value) {
        return new float[]{value, value, value, value};
    }

    @Test
    void writingTheSameEntryTwiceIsIdempotentAndPatchesOnlyWhatItNames() {
        VectorStore store = new VectorStore("memory");

        assertTrue(store.upsert("a", "diamond in the chest", vector(1f)));
        assertFalse(store.upsert("a", "diamond in the chest", vector(1f)));

        assertTrue(store.patch("a", "iron in the chest", vector(9f), true, false));
        assertEquals("iron in the chest", store.getEntry("a").content());
        // updateVector=false keeps the vector that was already there.
        assertTrue(java.util.Arrays.equals(vector(1f), store.getEntry("a").vector()));
    }

    @Test
    void entriesKeepInsertionOrderAndDeletingOneLeavesTheOthersFindable() {
        VectorStore store = new VectorStore("memory");
        store.upsert("a", "first", vector(1f));
        store.upsert("b", "second", vector(2f));
        store.upsert("c", "third", vector(3f));

        assertTrue(store.deleteEntry("b"));
        assertFalse(store.deleteEntry("b"));
        assertNull(store.getEntry("b"));
        assertNotNull(store.getEntry("a"));
        assertNotNull(store.getEntry("c"));

        List<String> order = store.getEntries().stream().map(VectorStore.EntrySnapshot::entryId).toList();
        assertEquals(List.of("a", "c"), order);
    }

    @Test
    void aLargeImportIsDoneInOnePassOverTheLibrary() {
        VectorStore store = new VectorStore("memory");
        int count = 20_000;

        long startedAt = System.nanoTime();
        for (int index = 0; index < count; index++) {
            store.upsert("entry-" + index, "fact number " + index, vector(index));
        }
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        assertEquals(count, store.size());
        assertTrue(store.hasEntry("entry-0"));
        assertTrue(store.hasEntry("entry-" + (count - 1)));
        for (int index = 0; index < count; index++) {
            assertEquals("fact number " + index, store.getEntry("entry-" + index).content());
        }
        // Generous on purpose: the point is to catch a scan per write (quadratic), not to benchmark.
        assertTrue(elapsedMillis < 5_000L,
                "a " + count + " entry import took " + elapsedMillis + " ms; the write path is not indexed");
    }
}