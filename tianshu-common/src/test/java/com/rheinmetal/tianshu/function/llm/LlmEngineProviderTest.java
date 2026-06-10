package com.rheinmetal.tianshu.function.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmEngineProviderTest {
    @TempDir
    Path tempDir;

    @Test
    void taskHotSuspendSlotsAreClampedToLibsSupportedRange() {
        assertEquals(0, LlmEngineProvider.taskHotSuspendSlots(-1));
        assertEquals(0, LlmEngineProvider.taskHotSuspendSlots(0));
        assertEquals(3, LlmEngineProvider.taskHotSuspendSlots(3));
        assertEquals(5, LlmEngineProvider.taskHotSuspendSlots(6));
    }

    @Test
    void aiServiceIsUnavailableWhenModelPathIsMissing() {
        TestLlmSupport.FakeGameEnvironment env = new TestLlmSupport.FakeGameEnvironment();
        LlmEngineProvider provider = new LlmEngineProvider(env, new TestLlmSupport.FakeConfig(tempDir));

        assertFalse(provider.isAiServiceAvailable());
        assertTrue(env.warnings.stream().anyMatch(message -> message.contains("LLM model path not configured")));
    }
}
