package com.rheinmetal.tianshu.function.llm.rag;

import com.rheinmetal.tianshu.core.scope.WorldScope;
import com.rheinmetal.tianshu.core.scope.WorldScopeKind;
import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmRagCacheLayoutTest {
    @TempDir
    Path tempDir;

    @Test
    void storesCacheUnderConfiguredRagCacheWorldDirectory() {
        TestLlmSupport.FakeConfig config = new TestLlmSupport.FakeConfig(tempDir);
        LlmRagCacheLayout layout = new LlmRagCacheLayout(
                config,
                () -> new WorldScope("user", "world:nether/test", "Nether", WorldScopeKind.LOCAL_WORLD, true)
        );

        assertEquals(
                config.getLlmBasePath().resolve("ragCache").resolve("world_nether_test"),
                layout.currentWorldCacheDirectory()
        );
    }

    @Test
    void usesUnknownWorldWhenScopeProviderIsMissing() {
        TestLlmSupport.FakeConfig config = new TestLlmSupport.FakeConfig(tempDir);
        LlmRagCacheLayout layout = new LlmRagCacheLayout(config, null);

        assertEquals(
                config.getLlmBasePath().resolve("ragCache").resolve("unknown_world"),
                layout.currentWorldCacheDirectory()
        );
    }
}
