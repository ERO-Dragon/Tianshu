package com.rheinmetal.tianshu.function.weaving.blackbox;

import com.rheinmetal.tianshu.core.runtime.RuntimeCapabilityState;
import com.rheinmetal.tianshu.function.weaving.LlmRuntimeCapabilities;
import com.rheinmetal.tianshu.function.weaving.contract.LLMCacheManagePayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMCacheManageResultPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPrimitiveQueryPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPrimitiveResultPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The RAG data plane without a vector lane: library lifecycle, content upsert/delete and lexical
 * search still work, and the capability state tells consumers that vector search is not available.
 */
class WeavingBlackBoxRagTest {
    @TempDir
    Path tempDir;

    @Test
    void theCacheManageCapabilityIsFailedUntilAVectorLaneIsBound() throws Exception {
        try (BlackBoxDriver driver = BlackBoxDriver.start(new BlackBoxConfig(tempDir, "http://127.0.0.1:1/v1"))) {
            assertEquals(RuntimeCapabilityState.FAILED,
                    driver.capabilityState(LlmRuntimeCapabilities.LLM_CACHE_MANAGE),
                    "no embedding model is loaded, so vector search is not available");
            assertEquals(RuntimeCapabilityState.READY,
                    driver.capabilityState(LlmRuntimeCapabilities.LLM_PRIMITIVE_QUERY));
        }
    }

    @Test
    void aLibraryRoundTripAndLexicalInlineSearchSurviveWithoutVectors() throws Exception {
        try (BlackBoxDriver driver = BlackBoxDriver.start(new BlackBoxConfig(tempDir, "http://127.0.0.1:1/v1"))) {
            LLMCacheManageResultPayload registered = driver.cache(
                    LLMCacheManagePayload.registerLibrary("kb", "modid", "SHARED", List.of("lore")));
            assertTrue(registered.success(), registered.errorMessage());

            LLMCacheManageResultPayload upserted = driver.cache(
                    LLMCacheManagePayload.upsertEntry("kb", "entry-1",
                            "the cat sat on the warm mat", new float[]{1f, 0f, 0f}));
            assertTrue(upserted.success(), upserted.errorMessage());

            LLMCacheManageResultPayload queried = driver.cache(LLMCacheManagePayload.queryUid("kb"));
            assertTrue(queried.success(), queried.errorMessage());
            assertTrue(queried.exists(), "an entry was written, so the uid now has a cache");
            assertEquals("kb", queried.libraries().get(0).uid());

            LLMCacheManageResultPayload searched = driver.cache(
                    LLMCacheManagePayload.searchUid("kb", "cat", 4, 0.01f));
            assertTrue(searched.success(), searched.errorMessage());
            assertFalse(searched.hits().isEmpty(), "a lexical match must be found without vectors");
            assertEquals("the cat sat on the warm mat",
                    searched.hits().get(0).entries().get(0).content());
        }
    }

    @Test
    void inlineContentsAreScoredWithoutTouchingStorage() throws Exception {
        try (BlackBoxDriver driver = BlackBoxDriver.start(new BlackBoxConfig(tempDir, "http://127.0.0.1:1/v1"))) {
            LLMCacheManageResultPayload searched = driver.cache(
                    LLMCacheManagePayload.searchInlineContents("kb", "cat",
                            List.of("the cat sat on the warm mat", "dogs bark at night"), 4, 0.01f));

            assertTrue(searched.success(), searched.errorMessage());
            assertFalse(searched.hits().isEmpty());
            assertEquals("the cat sat on the warm mat",
                    searched.hits().get(0).entries().get(0).content());
        }
    }

    @Test
    void deletingAnEntryRemovesItFromSearch() throws Exception {
        try (BlackBoxDriver driver = BlackBoxDriver.start(new BlackBoxConfig(tempDir, "http://127.0.0.1:1/v1"))) {
            driver.cache(LLMCacheManagePayload.registerLibrary("kb", "modid", "SHARED", List.of()));
            driver.cache(LLMCacheManagePayload.upsertEntry("kb", "entry-1", "unique needle text",
                    new float[]{1f, 0f, 0f}));

            LLMCacheManageResultPayload before = driver.cache(
                    LLMCacheManagePayload.searchUid("kb", "needle", 4, 0.01f));
            assertFalse(before.hits().isEmpty());

            LLMCacheManageResultPayload deleted = driver.cache(
                    LLMCacheManagePayload.deleteEntry("kb", "entry-1"));
            assertTrue(deleted.success(), deleted.errorMessage());

            LLMCacheManageResultPayload after = driver.cache(
                    LLMCacheManagePayload.searchUid("kb", "needle", 4, 0.01f));
            assertTrue(after.hits().isEmpty(), "a deleted entry must not be searchable");
        }
    }

    @Test
    void anUnknownActionComesBackAsAFailedResultInsteadOfThrowing() throws Exception {
        try (BlackBoxDriver driver = BlackBoxDriver.start(new BlackBoxConfig(tempDir, "http://127.0.0.1:1/v1"))) {
            LLMCacheManageResultPayload result = driver.cache(new LLMCacheManagePayload(
                    "NOT_AN_ACTION", "kb", "", "SHARED", List.of(), "", "", List.of(),
                    null, false, false, "", 4, 0.7f));

            assertFalse(result.success());
            assertTrue(result.errorMessage() != null && !result.errorMessage().isBlank());
        }
    }

    @Test
    void patchingContentKeepsTheEntrySearchable() throws Exception {
        try (BlackBoxDriver driver = BlackBoxDriver.start(new BlackBoxConfig(tempDir, "http://127.0.0.1:1/v1"))) {
            driver.cache(LLMCacheManagePayload.registerLibrary("kb", "modid", "SHARED", List.of()));
            driver.cache(LLMCacheManagePayload.upsertEntry("kb", "entry-1", "old needle text", vector()));

            LLMCacheManageResultPayload patched = driver.cache(LLMCacheManagePayload.patchEntry(
                    "kb", "entry-1", "new needle text", new float[0], true, false));
            assertTrue(patched.success(), patched.errorMessage());
            assertTrue(patched.exists());

            LLMCacheManageResultPayload searched = driver.cache(
                    LLMCacheManagePayload.searchUid("kb", "needle", 4, 0.01f));
            assertEquals("new needle text", searched.hits().get(0).entries().get(0).content());
        }
    }

    @Test
    void clearingAUidRemovesItsEntries() throws Exception {
        try (BlackBoxDriver driver = BlackBoxDriver.start(new BlackBoxConfig(tempDir, "http://127.0.0.1:1/v1"))) {
            driver.cache(LLMCacheManagePayload.registerLibrary("kb", "modid", "SHARED", List.of()));
            driver.cache(LLMCacheManagePayload.upsertEntry("kb", "entry-1", "needle text", vector()));

            LLMCacheManageResultPayload cleared = driver.cache(LLMCacheManagePayload.clearUid("kb"));
            assertTrue(cleared.success(), cleared.errorMessage());

            assertTrue(driver.cache(LLMCacheManagePayload.searchUid("kb", "needle", 4, 0.01f)).hits().isEmpty());
            assertFalse(driver.cache(LLMCacheManagePayload.queryUid("kb")).exists());
        }
    }

    @Test
    void unregisteringALibraryRemovesItsRegistration() throws Exception {
        try (BlackBoxDriver driver = BlackBoxDriver.start(new BlackBoxConfig(tempDir, "http://127.0.0.1:1/v1"))) {
            driver.cache(LLMCacheManagePayload.registerLibrary("kb", "modid", "SHARED", List.of("lore")));
            LLMCacheManageResultPayload unregistered = driver.cache(
                    LLMCacheManagePayload.unregisterLibrary("kb"));
            assertTrue(unregistered.success(), unregistered.errorMessage());

            LLMCacheManageResultPayload queried = driver.cache(LLMCacheManagePayload.queryUid("kb"));
            assertTrue(queried.libraries().isEmpty(), "the library is gone from the registry");
        }
    }

    @Test
    void sharedSearchByModidAndTagsNeverReturnsPrivateLibraries() throws Exception {
        try (BlackBoxDriver driver = BlackBoxDriver.start(new BlackBoxConfig(tempDir, "http://127.0.0.1:1/v1"))) {
            driver.cache(LLMCacheManagePayload.registerLibrary("shared-a", "loremod", "SHARED", List.of("lore")));
            driver.cache(LLMCacheManagePayload.registerLibrary("shared-b", "loremod", "SHARED", List.of("lore")));
            driver.cache(LLMCacheManagePayload.registerLibrary("private", "loremod", "PRIVATE", List.of("lore")));
            driver.cache(LLMCacheManagePayload.upsertEntry("shared-a", "a1", "dragon lore", vector()));
            driver.cache(LLMCacheManagePayload.upsertEntry("shared-b", "b1", "elf lore", vector()));
            driver.cache(LLMCacheManagePayload.upsertEntry("private", "p1", "secret lore", vector()));

            LLMCacheManageResultPayload byModid = driver.cache(
                    LLMCacheManagePayload.searchModid("loremod", "lore", 4, 0.01f));
            assertTrue(byModid.success(), byModid.errorMessage());
            assertFalse(byModid.hits().isEmpty());
            assertTrue(byModid.hits().stream().noneMatch(hit -> hit.uid().equals("private")),
                    "a private library is not part of the shared modid search");

            LLMCacheManageResultPayload byTag = driver.cache(
                    LLMCacheManagePayload.searchTags(List.of("lore"), "lore", 4, 0.01f));
            assertTrue(byTag.success(), byTag.errorMessage());
            assertFalse(byTag.hits().isEmpty());
            assertTrue(byTag.hits().stream().noneMatch(hit -> hit.uid().equals("private")),
                    "a private library is not part of the shared tag search");
        }
    }

    @Test
    void theCacheSurvivesAModuleRestart() throws Exception {
        BlackBoxConfig config = new BlackBoxConfig(tempDir, "http://127.0.0.1:1/v1");
        try (BlackBoxDriver first = BlackBoxDriver.start(config)) {
            first.cache(LLMCacheManagePayload.registerLibrary("kb", "modid", "SHARED", List.of()));
            first.cache(LLMCacheManagePayload.upsertEntry("kb", "entry-1", "persisted needle text", vector()));
        }

        try (BlackBoxDriver restarted = BlackBoxDriver.start(config)) {
            LLMCacheManageResultPayload queried = restarted.cache(LLMCacheManagePayload.queryUid("kb"));
            assertTrue(queried.exists(), "the cache file was flushed on shutdown and reloaded");

            LLMCacheManageResultPayload searched = restarted.cache(
                    LLMCacheManagePayload.searchUid("kb", "needle", 4, 0.01f));
            assertFalse(searched.hits().isEmpty(), "the entry is still searchable after a restart");
            assertEquals("persisted needle text", searched.hits().get(0).entries().get(0).content());
        }
    }

    @Test
    void patchingContentAndVectorKeepsTheEntrySearchable() throws Exception {
        try (BlackBoxDriver driver = BlackBoxDriver.start(new BlackBoxConfig(tempDir, "http://127.0.0.1:1/v1"))) {
            driver.cache(LLMCacheManagePayload.registerLibrary("kb", "modid", "SHARED", List.of()));
            driver.cache(LLMCacheManagePayload.upsertEntry("kb", "entry-1", "old needle text", vector()));

            LLMCacheManageResultPayload patched = driver.cache(LLMCacheManagePayload.patchEntry(
                    "kb", "entry-1", "vector patched text", new float[]{0f, 1f, 0f}, true, true));
            assertTrue(patched.success(), patched.errorMessage());
            assertTrue(patched.exists());

            LLMCacheManageResultPayload searched = driver.cache(
                    LLMCacheManagePayload.searchUid("kb", "patched", 4, 0.01f));
            assertFalse(searched.hits().isEmpty());
            assertEquals("vector patched text", searched.hits().get(0).entries().get(0).content());
        }
    }

    private static float[] vector() {
        return new float[]{1f, 0f, 0f};
    }

    @Test
    void tokenCountingAndStatusStillAnswerWithoutVectors() throws Exception {
        try (BlackBoxDriver driver = BlackBoxDriver.start(new BlackBoxConfig(tempDir, "http://127.0.0.1:1/v1"))) {
            LLMPrimitiveResultPayload tokens = driver.primitive(
                    LLMPrimitiveQueryPayload.tokenCount(
                            "tokens", "", List.of(
                                    LLMPrimitiveQueryPayload.MessageItemPayload.user("hello world"))));
            assertEquals("COMPLETED", tokens.status());
            assertTrue(tokens.tokenCount() > 0);

            LLMPrimitiveResultPayload status = driver.primitive(
                    LLMPrimitiveQueryPayload.status("status"));
            assertEquals("COMPLETED", status.status());
            assertTrue(status.runtimeSnapshot().ready());
            assertEquals(200_000, status.runtimeSnapshot().contextSize());
            assertTrue(status.runtimeSnapshot().contextTokenBudget() > 0);
            assertFalse(status.runtimeSnapshot().embeddingAvailable());
        }
    }
}