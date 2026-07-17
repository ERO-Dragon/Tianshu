package com.rheinmetal.tianshu.function.llm.service;

import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmRagStorageServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void storageSupportsVectorWritesAndBm25BeforeGenerationLoads() {
        LlmRagStorageService storage = new LlmRagStorageService(
                new TestLlmSupport.FakeGameEnvironment(),
                tempDir,
                "test",
                true,
                Runnable::run,
                RagPersistenceScheduler.immediate()
        );

        storage.registerLibrary("memory", "tianshu", "PRIVATE", List.of("ax"));
        assertTrue(storage.upsert("memory", "entry", "diamond pickaxe in ender chest", new float[]{1f, 0f}).success());
        assertTrue(storage.hasEntry("memory", "entry"));
        assertFalse(storage.searchEntries("memory", "where is the pickaxe", 4, 0.0f).isEmpty());
    }
}
