package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.function.llm.runtime.LlmControlResult;
import com.rheinmetal.tianshu.function.llm.runtime.LlmRuntimeState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmModuleServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void loadRejectsWhenDisabled() {
        LlmModuleService service = new LlmModuleService(new TestLlmSupport.FakeConfig(tempDir).llmEnabled(false));

        LlmControlResult result = service.load();

        assertFalse(result.accepted());
        assertEquals(LlmRuntimeState.DISABLED, service.snapshot().state());
    }

    @Test
    void loadFailsWhenRuntimeControllerIsNotBound() {
        LlmModuleService service = new LlmModuleService(new TestLlmSupport.FakeConfig(tempDir));

        LlmControlResult result = service.load();

        assertFalse(result.accepted());
        assertEquals(LlmRuntimeState.FAILED, service.snapshot().state());
        assertEquals("LLM runtime controller is not bound", service.snapshot().failureMessage());
    }

    @Test
    void loadStartsBoundRuntimeController() {
        LlmModuleService service = new LlmModuleService(new TestLlmSupport.FakeConfig(tempDir));
        AtomicInteger starts = new AtomicInteger();
        service.bindRuntimeController(new CountingRuntimeController(starts, new AtomicInteger()));

        LlmControlResult result = service.load();

        assertTrue(result.accepted());
        assertEquals(1, starts.get());
        assertEquals(LlmRuntimeState.STARTING, service.snapshot().state());
    }

    @Test
    void readyFailedAndStoppedRespectDisabledConfig() {
        TestLlmSupport.FakeConfig config = new TestLlmSupport.FakeConfig(tempDir).llmEnabled(false);
        LlmModuleService service = new LlmModuleService(config);

        service.markReady();
        assertEquals(LlmRuntimeState.DISABLED, service.snapshot().state());

        service.markFailed("boom");
        assertEquals(LlmRuntimeState.DISABLED, service.snapshot().state());

        service.markStopped();
        assertEquals(LlmRuntimeState.DISABLED, service.snapshot().state());
    }

    @Test
    void unloadStopsRuntimeAndMovesToStoppedWhenControllerMarksStopped() {
        LlmModuleService service = new LlmModuleService(new TestLlmSupport.FakeConfig(tempDir));
        AtomicInteger stops = new AtomicInteger();
        service.bindRuntimeController(new LlmModuleService.RuntimeController() {
            @Override public void start() {}
            @Override public void stop() {
                stops.incrementAndGet();
                service.markStopped();
            }
        });

        service.markReady();
        LlmControlResult result = service.unload();

        assertTrue(result.accepted());
        assertEquals(1, stops.get());
        assertEquals(LlmRuntimeState.STOPPED, service.snapshot().state());
    }

    private record CountingRuntimeController(AtomicInteger starts, AtomicInteger stops) implements LlmModuleService.RuntimeController {
        @Override public void start() { starts.incrementAndGet(); }
        @Override public void stop() { stops.incrementAndGet(); }
    }
}
