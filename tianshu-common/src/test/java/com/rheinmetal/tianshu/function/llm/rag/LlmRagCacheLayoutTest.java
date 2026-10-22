package com.rheinmetal.tianshu.function.llm.rag;

import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class LlmRagCacheLayoutTest {
    @TempDir
    Path tempDir;

    @Test
    void storesCacheUnderSingleConfiguredEntryDirectory() {
        TestLlmSupport.FakeConfig config = new TestLlmSupport.FakeConfig(tempDir);
        LlmRagCacheLayout layout = new LlmRagCacheLayout(config, null);

        assertEquals(
                config.getLlmBasePath().resolve("ragCache").resolve("entries"),
                layout.cacheDirectory()
        );
    }
    @Test
    void theNamespaceFollowsTheRuntimeIdentity() {
        TestLlmSupport.FakeConfig first = new TestLlmSupport.FakeConfig(tempDir);
        first.embeddingModelName("bge-m3");
        TestLlmSupport.FakeConfig second = new TestLlmSupport.FakeConfig(tempDir);
        second.embeddingModelName("bge-m3");
        second.customLlmName("another-runtime");

        assertNotEquals(
                new LlmRagCacheLayout(first, null).cacheNamespace(),
                new LlmRagCacheLayout(second, null).cacheNamespace(),
                "a different runtime identity gets its own namespace"
        );
    }
}
