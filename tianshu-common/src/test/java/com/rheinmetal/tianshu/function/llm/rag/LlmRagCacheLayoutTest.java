package com.rheinmetal.tianshu.function.llm.rag;

import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
