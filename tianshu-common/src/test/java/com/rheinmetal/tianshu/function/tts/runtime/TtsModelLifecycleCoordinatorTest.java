package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.function.tts.synthesis.TtsAudioSink;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsSynthesisEngine;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutorManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsModelLifecycleCoordinatorTest {
    private final ProtocolExecutorManager executors = new ProtocolExecutorManager(Runnable::run);

    @AfterEach
    void closeExecutors() {
        executors.close();
    }

    @Test
    void prepareInitializesEngineOnModelLoadLane() throws Exception {
        ThreadRecordingEngine engine = new ThreadRecordingEngine();
        TtsModelLifecycleCoordinator coordinator = new TtsModelLifecycleCoordinator(executors, engine, ignored -> { });
        String callerThread = Thread.currentThread().getName();
        CountDownLatch completed = new CountDownLatch(1);

        TtsOperationResult result = coordinator.prepare(initialized -> completed.countDown());

        assertTrue(result.accepted());
        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertNotEquals(callerThread, engine.initializeThread.get());
        assertTrue(engine.initializeThread.get().contains("MODEL_LOAD"));
    }

    @Test
    void useModelRunsOffCallerThread() throws Exception {
        ThreadRecordingEngine engine = new ThreadRecordingEngine();
        TtsModelLifecycleCoordinator coordinator = new TtsModelLifecycleCoordinator(executors, engine, ignored -> { });
        String callerThread = Thread.currentThread().getName();
        CountDownLatch completed = new CountDownLatch(1);

        TtsOperationResult result = coordinator.useModel("voice-model", control -> completed.countDown());

        assertTrue(result.accepted());
        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertNotEquals(callerThread, engine.useModelThread.get());
        assertTrue(engine.useModelThread.get().contains("MODEL_LOAD"));
    }

    private static final class ThreadRecordingEngine implements TtsSynthesisEngine {
        private final AtomicReference<String> initializeThread = new AtomicReference<>();
        private final AtomicReference<String> useModelThread = new AtomicReference<>();

        @Override public boolean initialize() { initializeThread.set(Thread.currentThread().getName()); return true; }
        @Override public boolean isInitialized() { return initializeThread.get() != null || useModelThread.get() != null; }
        @Override public boolean isAutoregressive() { return false; }
        @Override public int sampleRate() { return 24_000; }
        @Override public TtsBackendSnapshot backendSnapshot() { return TtsBackendSnapshot.unavailable(); }
        @Override public boolean useModel(String modelName) { useModelThread.set(Thread.currentThread().getName()); return true; }
        @Override public void synthesize(TtsRequest request, TtsAudioSink sink) { }
        @Override public void interrupt() { }
        @Override public void shutdown() { }
    }
}
