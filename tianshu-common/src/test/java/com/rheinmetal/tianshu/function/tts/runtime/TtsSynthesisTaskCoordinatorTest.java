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
        List<Boolean> terminal = new ArrayList<>();
        AtomicInteger completions = new AtomicInteger();
        CountDownLatch completed = new CountDownLatch(1);

        TtsOperationResult result = coordinator.submit(
                request("full"),
                false,
                30_000L,
                (index, chunk, last) -> {
                    audio.add(chunk);
                    terminal.add(last);
                },
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
        assertEquals(List.of(true), terminal);
        assertEquals(1, completions.get());
    }

    @Test
    void streamingSynthesisEndsWithOneEmptyTerminalChunk() throws Exception {
        ChunkEngine engine = new ChunkEngine();
        TtsSynthesisTaskCoordinator coordinator = coordinator(engine);
        List<byte[]> audio = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Boolean> terminal = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch completed = new CountDownLatch(1);

        coordinator.submit(request("stream"), true, 30_000L, (index, chunk, last) -> {
            audio.add(chunk);
            terminal.add(last);
        }, completed::countDown, failure -> { });

        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertEquals(3, audio.size());
        assertArrayEquals(new byte[0], audio.get(2));
        assertEquals(List.of(false, false, true), terminal);
    }

    @Test
    void duplicateRequestIdIsRejectedWithoutReplacingOriginalTask() throws Exception {
        BlockingEngine engine = new BlockingEngine();
        TtsSynthesisTaskCoordinator coordinator = coordinator(engine);
        try {
            assertTrue(coordinator.submit(request("duplicate"), false, 30_000L,
                    (index, chunk, last) -> { }, () -> { }, failure -> { }).accepted());
            assertTrue(engine.started.await(2L, TimeUnit.SECONDS));

            assertFalse(coordinator.submit(request("duplicate"), false, 30_000L,
                    (index, chunk, last) -> { }, () -> { }, failure -> { }).accepted());
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
            assertTrue(coordinator.submit(request("expires"), false, 1_000L,
                    (index, chunk, last) -> { }, () -> { }, failure -> {
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
}
