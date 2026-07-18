package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.function.tts.synthesis.TtsAudioSink;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsSynthesisEngine;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutorManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void shutdownWaitsForActiveAtomicSynthesisWork() throws Exception {
        BlockingEngine engine = new BlockingEngine();
        TtsSynthesisScheduler scheduler = new TtsSynthesisScheduler(executors, engine);
        TtsModelLifecycleCoordinator coordinator = new TtsModelLifecycleCoordinator(executors, engine, ignored -> { });
        Object synthesisOwner = new Object();
        scheduler.submit(new TtsRequest(
                "request", "request", "request", "request", "text",
                TtsRequestSource.SYSTEM, TtsPlaybackPolicy.QUEUE,
                com.rheinmetal.tianshu.protocol.Priority.NORMAL, TtsVoiceProfile.defaults()
        ), synthesisOwner, () -> engine.synthesize(null, null));
        assertTrue(engine.started.await(2L, TimeUnit.SECONDS));

        coordinator.shutdown();

        assertFalse(engine.shutdownFinished.await(150L, TimeUnit.MILLISECONDS));
        engine.release.countDown();
        assertTrue(engine.shutdownFinished.await(2L, TimeUnit.SECONDS));
    }

    @Test
    void repeatedPrepareDuringActiveLoadSharesTheSameResult() throws Exception {
        BlockingPrepareEngine engine = new BlockingPrepareEngine();
        TtsModelLifecycleCoordinator coordinator = new TtsModelLifecycleCoordinator(executors, engine, ignored -> { });
        CountDownLatch completions = new CountDownLatch(2);

        assertTrue(coordinator.prepare(ignored -> completions.countDown()).accepted());
        assertTrue(engine.initializeStarted.await(2L, TimeUnit.SECONDS));
        assertTrue(coordinator.prepare(ignored -> completions.countDown()).accepted());
        engine.releaseInitialize.countDown();

        assertTrue(completions.await(2L, TimeUnit.SECONDS));
        assertTrue(engine.initializeCalls.get() == 1);
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

    private static final class BlockingEngine implements TtsSynthesisEngine {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch shutdownFinished = new CountDownLatch(1);
        private final AtomicBoolean shutdownCalled = new AtomicBoolean();
        @Override public boolean initialize() { return true; }
        @Override public boolean isInitialized() { return true; }
        @Override public boolean isAutoregressive() { return false; }
        @Override public int sampleRate() { return 24_000; }
        @Override public TtsBackendSnapshot backendSnapshot() { return TtsBackendSnapshot.unavailable(); }
        @Override public boolean useModel(String modelName) { return true; }
        @Override public void synthesize(TtsRequest request, TtsAudioSink sink) {
            started.countDown();
            try {
                release.await(2L, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        @Override public void interrupt() { }
        @Override public void shutdown() { shutdownCalled.set(true); shutdownFinished.countDown(); }
    }

    private static final class BlockingPrepareEngine implements TtsSynthesisEngine {
        private final CountDownLatch initializeStarted = new CountDownLatch(1);
        private final CountDownLatch releaseInitialize = new CountDownLatch(1);
        private final AtomicInteger initializeCalls = new AtomicInteger();

        @Override
        public boolean initialize() {
            initializeCalls.incrementAndGet();
            initializeStarted.countDown();
            try {
                releaseInitialize.await(2L, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
            return true;
        }

        @Override public boolean preloadVoice(TtsVoiceProfile voiceProfile) { return true; }
        @Override public boolean isInitialized() { return initializeCalls.get() > 0; }
        @Override public boolean isAutoregressive() { return false; }
        @Override public int sampleRate() { return 24_000; }
        @Override public TtsBackendSnapshot backendSnapshot() { return TtsBackendSnapshot.unavailable(); }
        @Override public boolean useModel(String modelName) { return true; }
        @Override public void synthesize(TtsRequest request, TtsAudioSink sink) { }
        @Override public void interrupt() { }
        @Override public void shutdown() { }
    }
}
