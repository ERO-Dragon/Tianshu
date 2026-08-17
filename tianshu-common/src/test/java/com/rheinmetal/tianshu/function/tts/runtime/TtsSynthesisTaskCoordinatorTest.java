package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.function.tts.synthesis.TtsAudioSink;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsSynthesisEngine;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutorManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsSynthesisTaskCoordinatorTest {
    private final ProtocolExecutorManager executors = new ProtocolExecutorManager(Runnable::run);

    @AfterEach
    void closeExecutors() {
        executors.close();
    }

    @Test
    void fullSynthesisMergesChunksAndCompletesOnce() throws Exception {
        ChunkEngine engine = new ChunkEngine();
        TtsSynthesisTaskCoordinator coordinator = coordinator(engine);
        List<byte[]> audio = new ArrayList<>();
        AtomicInteger completions = new AtomicInteger();
        CountDownLatch completed = new CountDownLatch(1);

        TtsOperationResult result = coordinator.submit(
                request("full"),
                30_000L,
                audio::add,
                null,
                () -> {
                    completions.incrementAndGet();
                    completed.countDown();
                },
                failure -> { }
        );

        assertTrue(result.accepted());
        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertEquals(1, audio.size());
        assertArrayEquals(new byte[]{1, 2, 3}, audio.getFirst());
        assertEquals(1, completions.get());
    }


    @Test
    void synthesisActivityStartsOnlyWhenExecutionBeginsAndEndsAtTerminalCallback() throws Exception {
        ChunkEngine engine = new ChunkEngine();
        TtsSynthesisTaskCoordinator coordinator = coordinator(engine);
        List<String> lifecycle = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch completed = new CountDownLatch(1);

        TtsOperationResult result = coordinator.submit(
                request("activity"),
                30_000L,
                audio -> { },
                () -> lifecycle.add("started"),
                () -> {
                    lifecycle.add("completed");
                    completed.countDown();
                },
                failure -> lifecycle.add("failed")
        );

        assertTrue(result.accepted());
        assertTrue(completed.await(2L, TimeUnit.SECONDS));
        assertEquals(List.of("started", "completed"), lifecycle);
    }

    @Test
    void acknowledgedHandoffDeliversOneCompleteRequestAndCompletesAfterOwnershipTransfer() throws Exception {
        ChunkEngine engine = new ChunkEngine();
        TtsSynthesisTaskCoordinator coordinator = coordinator(engine, 1);
        List<byte[]> deliveredAudio = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch delivered = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);

        assertTrue(coordinator.submitAcknowledged(
                request("owned"),
                30_000L,
                "module.npc",
                audio -> {
                    deliveredAudio.add(audio);
                    delivered.countDown();
                },
                null,
                completed::countDown,
                failure -> { }
        ).accepted());

        assertTrue(delivered.await(2L, TimeUnit.SECONDS));
        assertEquals(1, deliveredAudio.size());
        assertArrayEquals(new byte[]{1, 2, 3}, deliveredAudio.getFirst());
        assertFalse(completed.await(150L, TimeUnit.MILLISECONDS));
        assertFalse(coordinator.acknowledge("module.other", "owned").accepted());

        assertTrue(coordinator.acknowledge("module.npc", "owned").accepted());
        assertTrue(completed.await(2L, TimeUnit.SECONDS));
    }

    @Test
    void acknowledgedHandoffCapacityCountsRequestsAndStartsNextAfterAck() throws Exception {
        CountingChunkEngine engine = new CountingChunkEngine();
        TtsSynthesisTaskCoordinator coordinator = coordinator(engine, 1);
        CountDownLatch firstDelivered = new CountDownLatch(1);
        CountDownLatch secondDelivered = new CountDownLatch(1);

        assertTrue(coordinator.submitAcknowledged(
                request("first"), 30_000L, "module.npc",
                audio -> firstDelivered.countDown(), null, () -> { }, failure -> { }
        ).accepted());
        assertTrue(firstDelivered.await(2L, TimeUnit.SECONDS));

        assertTrue(coordinator.submitAcknowledged(
                request("second"), 30_000L, "module.npc",
                audio -> secondDelivered.countDown(), null, () -> { }, failure -> { }
        ).accepted());
        assertFalse(engine.secondStarted.await(150L, TimeUnit.MILLISECONDS));

        assertTrue(coordinator.acknowledge("module.npc", "first").accepted());
        assertTrue(engine.secondStarted.await(2L, TimeUnit.SECONDS));
        assertTrue(secondDelivered.await(2L, TimeUnit.SECONDS));
        assertTrue(coordinator.acknowledge("module.npc", "second").accepted());
    }

    @Test
    void acknowledgedHandoffRejectsEmptyAudio() throws Exception {
        TtsSynthesisTaskCoordinator coordinator = coordinator(new SilentEngine(), 1);
        AtomicInteger deliveries = new AtomicInteger();
        AtomicReference<TtsFailure> observed = new AtomicReference<>();
        CountDownLatch failed = new CountDownLatch(1);
        try {
            assertTrue(coordinator.submitAcknowledged(
                    request("silent"),
                    30_000L,
                    "module.npc",
                    audio -> deliveries.incrementAndGet(),
                    null,
                    () -> { },
                    failure -> {
                        observed.set(failure);
                        failed.countDown();
                    }
            ).accepted());

            assertTrue(failed.await(2L, TimeUnit.SECONDS));
            assertEquals(0, deliveries.get());
            assertEquals(TtsFailureCode.SYNTHESIS_FAILED, observed.get().code());
        } finally {
            coordinator.cancelAll("test cleanup");
        }
    }

    @Test
    void duplicateRequestIdIsRejectedWithoutReplacingOriginalTask() throws Exception {
        BlockingEngine engine = new BlockingEngine();
        TtsSynthesisTaskCoordinator coordinator = coordinator(engine);
        try {
            assertTrue(coordinator.submit(request("duplicate"), 30_000L,
                    audio -> { }, null, () -> { }, failure -> { }).accepted());
            assertTrue(engine.started.await(2L, TimeUnit.SECONDS));

            assertFalse(coordinator.submit(request("duplicate"), 30_000L,
                    audio -> { }, null, () -> { }, failure -> { }).accepted());
        } finally {
            coordinator.cancelAll("test cleanup");
            engine.release.countDown();
        }
    }

    @Test
    void ttlInterruptsLongRunningSentenceAndReportsExpired() throws Exception {
        BlockingEngine engine = new BlockingEngine();
        TtsSynthesisTaskCoordinator coordinator = coordinator(engine);
        AtomicReference<TtsFailure> observed = new AtomicReference<>();
        CountDownLatch failed = new CountDownLatch(1);
        try {
            assertTrue(coordinator.submit(request("expires"), 1_000L,
                    audio -> { }, null, () -> { }, failure -> {
                        observed.set(failure);
                        failed.countDown();
                    }).accepted());
            assertTrue(engine.started.await(2L, TimeUnit.SECONDS));

            assertTrue(failed.await(2L, TimeUnit.SECONDS));
            assertEquals(TtsFailureCode.EXPIRED, observed.get().code());
            assertTrue(engine.interruptions.get() > 0);
        } finally {
            coordinator.cancelAll("test cleanup");
            engine.release.countDown();
        }
    }

    private TtsSynthesisTaskCoordinator coordinator(TtsSynthesisEngine engine) {
        TtsSynthesisScheduler scheduler = new TtsSynthesisScheduler(executors, engine);
        return new TtsSynthesisTaskCoordinator(engine, scheduler, new TtsAdaptiveSynthesisPolicy(), ignored -> { });
    }

    private TtsSynthesisTaskCoordinator coordinator(TtsSynthesisEngine engine, int handoffWindow) {
        TtsSynthesisScheduler scheduler = new TtsSynthesisScheduler(executors, engine);
        return new TtsSynthesisTaskCoordinator(
                engine,
                scheduler,
                new TtsAdaptiveSynthesisPolicy(),
                ignored -> { },
                handoffWindow
        );
    }

    private static TtsRequest request(String id) {
        return new TtsRequest(id, id, id, id, "hello", TtsRequestSource.SYSTEM,
                TtsPlaybackPolicy.QUEUE, Priority.NORMAL, TtsVoiceProfile.defaults());
    }

    private static final class ChunkEngine implements TtsSynthesisEngine {
        @Override public boolean initialize() { return true; }
        @Override public boolean isInitialized() { return true; }
        @Override public boolean isAutoregressive() { return false; }
        @Override public int sampleRate() { return 24_000; }
        @Override public TtsBackendSnapshot backendSnapshot() { return TtsBackendSnapshot.unavailable(); }
        @Override public boolean useModel(String modelName) { return true; }

        @Override
        public void synthesize(TtsRequest request, TtsAudioSink sink) {
            sink.accept(new byte[]{1});
            sink.accept(new byte[]{2, 3});
        }

        @Override public void interrupt() { }
        @Override public void shutdown() { }
    }


    private static final class CountingChunkEngine implements TtsSynthesisEngine {
        private final AtomicInteger starts = new AtomicInteger();
        private final CountDownLatch secondStarted = new CountDownLatch(1);

        @Override public boolean initialize() { return true; }
        @Override public boolean isInitialized() { return true; }
        @Override public boolean isAutoregressive() { return false; }
        @Override public int sampleRate() { return 24_000; }
        @Override public TtsBackendSnapshot backendSnapshot() { return TtsBackendSnapshot.unavailable(); }
        @Override public boolean useModel(String modelName) { return true; }

        @Override
        public void synthesize(TtsRequest request, TtsAudioSink sink) {
            if (starts.incrementAndGet() == 2) {
                secondStarted.countDown();
            }
            sink.accept(new byte[]{1});
            sink.accept(new byte[]{2, 3});
        }

        @Override public void interrupt() { }
        @Override public void shutdown() { }
    }

    private static final class BlockingEngine implements TtsSynthesisEngine {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger interruptions = new AtomicInteger();

        @Override public boolean initialize() { return true; }
        @Override public boolean isInitialized() { return true; }
        @Override public boolean isAutoregressive() { return false; }
        @Override public int sampleRate() { return 24_000; }
        @Override public TtsBackendSnapshot backendSnapshot() { return TtsBackendSnapshot.unavailable(); }
        @Override public boolean useModel(String modelName) { return true; }

        @Override
        public void synthesize(TtsRequest request, TtsAudioSink sink) {
            started.countDown();
            try {
                release.await(3L, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            sink.accept(new byte[]{1});
        }

        @Override
        public void interrupt() {
            interruptions.incrementAndGet();
            release.countDown();
        }

        @Override public void shutdown() { }
    }

    private static final class SilentEngine implements TtsSynthesisEngine {
        @Override public boolean initialize() { return true; }
        @Override public boolean isInitialized() { return true; }
        @Override public boolean isAutoregressive() { return false; }
        @Override public int sampleRate() { return 24_000; }
        @Override public TtsBackendSnapshot backendSnapshot() { return TtsBackendSnapshot.unavailable(); }
        @Override public boolean useModel(String modelName) { return true; }
        @Override public void synthesize(TtsRequest request, TtsAudioSink sink) { }
        @Override public void interrupt() { }
        @Override public void shutdown() { }
    }
}
