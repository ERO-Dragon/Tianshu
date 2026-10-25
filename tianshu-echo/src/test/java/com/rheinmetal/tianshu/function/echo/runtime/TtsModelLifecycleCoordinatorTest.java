package com.rheinmetal.tianshu.function.echo.runtime;

import com.rheinmetal.tianshu.function.echo.synthesis.TtsAudioSink;
import com.rheinmetal.tianshu.function.echo.synthesis.TtsSynthesisEngine;
import com.rheinmetal.tianshu.model.NativeModelLoadQueue;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutorManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        List<Boolean> loading = java.util.Collections.synchronizedList(new ArrayList<>());
        TtsModelLifecycleCoordinator coordinator = new TtsModelLifecycleCoordinator(executors, engine, ignored -> { }, loading::add);
        String callerThread = Thread.currentThread().getName();
        CountDownLatch completed = new CountDownLatch(1);

        TtsOperationResult result = coordinator.prepare(initialized -> completed.countDown());

        assertTrue(result.accepted());
        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertNotEquals(callerThread, engine.initializeThread.get());
        assertTrue(engine.initializeThread.get().contains("MODEL_LOAD"));
        // The completion callback and the loading pulse travel on different paths: the pulse is cleared by the
        // lane wrapper after the work returns, so it can land a hair after the completion. Wait for it instead
        // of asserting on a half-filled list — this assertion only ever ran green when the machine was idle.
        awaitLoadingPulse(loading);
        assertEquals(List.of(true, false), loading);
    }

    private static void awaitLoadingPulse(List<Boolean> loading) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            synchronized (loading) {
                if (loading.size() >= 2) {
                    return;
                }
            }
            Thread.sleep(5L);
        }
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

    /**
     * A second switch asked while one is loading is merged, not rejected and not queued: the newest target
     * wins, both callers are answered, and the model is loaded once more for the target that actually
     * changed.
     */
    @Test
    void aSwitchAskedWhileAnotherIsLoadingIsCoalesced() throws Exception {
        BlockingSwitchEngine engine = new BlockingSwitchEngine();
        TtsModelLifecycleCoordinator coordinator = new TtsModelLifecycleCoordinator(executors, engine, ignored -> { });
        CountDownLatch completions = new CountDownLatch(2);

        assertTrue(coordinator.useModel("first", result -> completions.countDown()).accepted());
        assertTrue(engine.firstSwitchStarted.await(2L, TimeUnit.SECONDS));
        assertTrue(coordinator.useModel("second", result -> completions.countDown()).accepted(),
                "a request that arrives during a switch must be merged, not rejected as busy");

        engine.releaseFirstSwitch.countDown();

        assertTrue(completions.await(2L, TimeUnit.SECONDS), "both callers must be answered");
        assertEquals(List.of("first", "second"), engine.requested,
                "the load has to follow the newest target instead of the one that was already superseded");
    }

    /**
     * Shutdown is terminal, so a switch still parked in the gate has to be answered.
     *
     * <p>It used to be dropped: the caller waited for an outcome that could never arrive, because the load
     * it was waiting on would never run. Exactly one outcome, and it is a rejection.
     */
    @Test
    void shutdownAnswersASwitchParkedInTheGate() throws Exception {
        TtsModelLifecycleCoordinator coordinator = new TtsModelLifecycleCoordinator(
                executors, new ThreadRecordingEngine(), ignored -> { }, ignored -> { }, gateHeldElsewhere());
        List<TtsControlResult> outcomes = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch answered = new CountDownLatch(1);

        assertTrue(coordinator.useModel("voice-model", outcome -> {
            outcomes.add(outcome);
            answered.countDown();
        }).accepted());
        coordinator.shutdown();
        // A second teardown must not answer the same caller again.
        coordinator.shutdown();

        assertTrue(answered.await(2L, TimeUnit.SECONDS), "shutdown must answer the in-flight switch, not drop it");
        assertEquals(1, outcomes.size(), "exactly one outcome per waiting caller");
        assertFalse(outcomes.get(0).accepted(), "a module that went away did not switch the model");
        assertEquals(TtsFailureCode.CANCELLED, outcomes.get(0).failure().code());
    }

    @Test
    void shutdownAnswersAPrepareParkedInTheGate() throws Exception {
        TtsModelLifecycleCoordinator coordinator = new TtsModelLifecycleCoordinator(
                executors, new ThreadRecordingEngine(), ignored -> { }, ignored -> { }, gateHeldElsewhere());
        List<Boolean> outcomes = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch answered = new CountDownLatch(1);

        assertTrue(coordinator.prepare(initialized -> {
            outcomes.add(initialized);
            answered.countDown();
        }).accepted());
        coordinator.shutdown();

        assertTrue(answered.await(2L, TimeUnit.SECONDS), "shutdown must answer the in-flight prepare, not drop it");
        assertEquals(1, outcomes.size(), "exactly one outcome per waiting caller");
        assertFalse(outcomes.get(0), "a prepare that never ran did not initialize the engine");
    }

    @Test
    void shutdownAnswersAPreviewParkedInTheGate() throws Exception {
        TtsModelLifecycleCoordinator coordinator = new TtsModelLifecycleCoordinator(
                executors, new ThreadRecordingEngine(), ignored -> { }, ignored -> { }, gateHeldElsewhere());
        List<TtsFailure> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch answered = new CountDownLatch(1);

        assertTrue(coordinator.beginExclusivePreview(
                "request-1",
                "preview-model",
                "restore-model",
                null,
                null,
                failure -> {
                    failures.add(failure);
                    answered.countDown();
                }
        ).accepted());
        coordinator.shutdown();

        assertTrue(answered.await(2L, TimeUnit.SECONDS), "shutdown must answer the in-flight preview, not drop it");
        assertEquals(1, failures.size(), "exactly one failure per waiting preview");
        assertEquals(TtsFailureCode.CANCELLED, failures.get(0).code());
    }

    /** A gate whose permit is held by another module forever, so every request from here parks. */
    private static NativeModelLoadQueue gateHeldElsewhere() {
        NativeModelLoadQueue queue = new NativeModelLoadQueue();
        assertEquals(NativeModelLoadQueue.Outcome.GRANTED, queue.request("seed", "seed", () -> { },
                NativeModelLoadQueue.Tier.PRIMARY));
        return queue;
    }

    private static final class BlockingSwitchEngine implements TtsSynthesisEngine {
        private final CountDownLatch firstSwitchStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFirstSwitch = new CountDownLatch(1);
        private final List<String> requested = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public boolean useModel(String modelName) {
            requested.add(modelName);
            if (requested.size() == 1) {
                firstSwitchStarted.countDown();
                try {
                    releaseFirstSwitch.await(2L, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }

        @Override public boolean initialize() { return true; }
        @Override public boolean isInitialized() { return !requested.isEmpty(); }
        @Override public boolean isAutoregressive() { return false; }
        @Override public int sampleRate() { return 24_000; }
        @Override public TtsBackendSnapshot backendSnapshot() { return TtsBackendSnapshot.unavailable(); }
        @Override public void synthesize(TtsRequest request, TtsAudioSink sink) { }
        @Override public void interrupt() { }
        @Override public void shutdown() { }
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
