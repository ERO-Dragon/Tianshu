package com.rheinmetal.tianshu.function.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmEngineProviderTest {
    @TempDir
    Path tempDir;

    @Test
    void aiServiceIsUnavailableWhenModelPathIsNotConfigured() {
        TestLlmSupport.FakeGameEnvironment env = new TestLlmSupport.FakeGameEnvironment();
        LlmEngineProvider provider = new LlmEngineProvider(env, new TestLlmSupport.FakeConfig(tempDir));

        assertFalse(provider.isAiServiceAvailable());
        assertTrue(env.warnings.isEmpty());
    }
}
