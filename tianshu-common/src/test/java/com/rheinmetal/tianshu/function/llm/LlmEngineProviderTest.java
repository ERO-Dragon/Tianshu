package com.rheinmetal.tianshu.function.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void unconfiguredStartDoesNotReportModelLoading() {
        TestLlmSupport.FakeGameEnvironment env = new TestLlmSupport.FakeGameEnvironment();
        LlmEngineProvider provider = new LlmEngineProvider(env, new TestLlmSupport.FakeConfig(tempDir).customLlmName(""));
        AtomicInteger loadingStarts = new AtomicInteger();
        AtomicInteger loadingEnds = new AtomicInteger();

        provider.startAsync(loadingStarts::incrementAndGet, () -> {}, () -> {}, loadingEnds::incrementAndGet);

        assertEquals(0, loadingStarts.get());
        assertEquals(0, loadingEnds.get());
    }

    @Test
    void loadFailureAttributesIncludeCauseChainAndMemorySnapshot() {
        Map<String, String> attributes = LlmEngineProvider.loadFailureAttributes(
                new IllegalStateException("load failed", new RuntimeException("device memory exhausted"))
        );

        assertEquals(
                "java.lang.IllegalStateException: load failed <- java.lang.RuntimeException: device memory exhausted",
                attributes.get("failureChain")
        );
        assertTrue(Long.parseLong(attributes.get("jvmHeapUsedBytes")) >= 0L);
        assertTrue(Long.parseLong(attributes.get("jvmHeapMaxBytes")) > 0L);
        assertTrue(Long.parseLong(attributes.get("systemAvailableMemoryBytes")) >= -1L);
    }
}
