package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsSynthesisEngine;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutorManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsRuntimeControlTest {
    private final ProtocolExecutorManager executorManager = new ProtocolExecutorManager(Runnable::run);

    @AfterEach
    void closeExecutor() {
        executorManager.close();
    }

    @Test
    void submitStreamRejectsWhenRuntimeIsNotRunning() {
        CountingSynthesisEngine engine = new CountingSynthesisEngine();
        TtsRuntime runtime = runtime(engine, new ArrayList<>());
        AtomicReference<TtsFailure> failureRef = new AtomicReference<>();

        TtsOperationResult result = runtime.submitStream(chunk("stream-1", "hello", true), null, failureRef::set);

        assertFalse(result.accepted());
        assertEquals(TtsFailureCode.RUNTIME_NOT_RUNNING, result.failure().code());
        assertEquals(TtsFailureCode.RUNTIME_NOT_RUNNING, failureRef.get().code());
        assertEquals(0, engine.invocations.get());
    }

    @Test
    void submitStreamRejectsNullChunkWhenRunning() {
        CountingSynthesisEngine engine = new CountingSynthesisEngine();
        TtsRuntime runtime = runtime(engine, new ArrayList<>());
        prepareRuntime(runtime);
        AtomicReference<TtsFailure> failureRef = new AtomicReference<>();

        TtsOperationResult result = runtime.submitStream(null, null, failureRef::set);

        assertFalse(result.accepted());
        assertEquals(TtsFailureCode.INVALID_REQUEST, result.failure().code());
        assertEquals(TtsFailureCode.INVALID_REQUEST, failureRef.get().code());
    }

    @Test
    void submitStreamBuffersPartialTextUntilFinalChunk() throws Exception {
        CountingSynthesisEngine engine = new CountingSynthesisEngine();
        List<TtsSession> statuses = Collections.synchronizedList(new ArrayList<>());
        TtsRuntime runtime = runtime(engine, statuses);
        prepareRuntime(runtime);

        TtsOperationResult first = runtime.submitStream(chunk("stream-1", "hello", false), null, null);
        TtsOperationResult last = runtime.submitStream(chunk("stream-1", " world", true), null, null);

        assertTrue(first.accepted());
        assertTrue(last.accepted());
        assertTrue(engine.awaitInvocations(1));
        assertEquals("hello world", engine.lastText.get());
        assertTrue(statuses.stream().anyMatch(session -> session.request().requestId().startsWith("stream-1:")));
    }

    @Test
    void synthesizeFullReturnsAudioWithoutCreatingPlaybackSession() throws Exception {
        CountingSynthesisEngine engine = new CountingSynthesisEngine();
        List<TtsSession> statuses = Collections.synchronizedList(new ArrayList<>());
        TtsRuntime runtime = runtime(engine, statuses);
        prepareRuntime(runtime);
        List<byte[]> chunks = Collections.synchronizedList(new ArrayList<>());
        List<Boolean> lastFlags = Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.CountDownLatch completed = new java.util.concurrent.CountDownLatch(1);

        TtsOperationResult result = runtime.synthesize(
                request("synth-only"),
                false,
                (chunkIndex, audio, last) -> {
                    chunks.add(audio);
                    lastFlags.add(last);
                },
                completed::countDown,
                null
        );

        assertTrue(result.accepted());
        assertTrue(completed.await(2L, TimeUnit.SECONDS));
        assertEquals(1, chunks.size());
        assertEquals(2, chunks.get(0).length);
        assertEquals(List.of(true), lastFlags);
        assertTrue(statuses.isEmpty());
        assertEquals(1, engine.invocations.get());
    }

    @Test
    void localSpeakPreemptsActiveSynthesisTask() throws Exception {
        BlockingSynthesisEngine engine = new BlockingSynthesisEngine();
        List<TtsSession> statuses = Collections.synchronizedList(new ArrayList<>());
        TtsRuntime runtime = runtime(engine, statuses);
        prepareRuntime(runtime);
        AtomicReference<TtsFailure> taskFailure = new AtomicReference<>();
        CountDownLatch failed = new CountDownLatch(1);

        runtime.synthesize(
                request("synthesis-task"),
                false,
                30_000L,
                null,
                null,
                failure -> {
                    taskFailure.set(failure);
                    failed.countDown();
                }
        );
        assertTrue(engine.awaitStarted());

        TtsOperationResult speakResult = runtime.submit(request("local-speak"), null, null);

        assertTrue(speakResult.accepted());
        assertTrue(failed.await(2L, TimeUnit.SECONDS));
        assertEquals(TtsFailureCode.CANCELLED, taskFailure.get().code());
        assertTrue(engine.awaitInvocations(2));
        assertTrue(awaitState(statuses, "local-speak", TtsSessionState.COMPLETED));
    }

    @Test
    void queuedSynthesisTaskExpiresBeforeItStarts() throws Exception {
        BlockingSynthesisEngine engine = new BlockingSynthesisEngine();
        TtsRuntime runtime = runtime(engine, Collections.synchronizedList(new ArrayList<>()));
        prepareRuntime(runtime);
        AtomicReference<TtsFailure> expiredFailure = new AtomicReference<>();
        CountDownLatch expired = new CountDownLatch(1);

        runtime.synthesize(request("blocking-task"), false, 30_000L, null, null, null);
        assertTrue(engine.awaitStarted());
        runtime.synthesize(
                request("short-lived-task"),
                false,
                1_000L,
                null,
                null,
                failure -> {
                    expiredFailure.set(failure);
                    expired.countDown();
                }
        );

        Thread.sleep(1_150L);
        engine.release();

        assertTrue(expired.await(2L, TimeUnit.SECONDS));
        assertEquals(TtsFailureCode.EXPIRED, expiredFailure.get().code());
    }

    @Test
    void stopRequestCancelsQueuedSynthesisTask() throws Exception {
        BlockingSynthesisEngine engine = new BlockingSynthesisEngine();
        TtsRuntime runtime = runtime(engine, Collections.synchronizedList(new ArrayList<>()));
        prepareRuntime(runtime);
        AtomicReference<TtsFailure> taskFailure = new AtomicReference<>();
        CountDownLatch failed = new CountDownLatch(1);

        runtime.synthesize(request("blocking-task"), false, 30_000L, null, null, null);
        assertTrue(engine.awaitStarted());
        runtime.synthesize(
                request("queued-task"),
                false,
                30_000L,
                null,
                null,
                failure -> {
                    taskFailure.set(failure);
                    failed.countDown();
                }
        );

        TtsControlResult result = runtime.stopRequest("queued-task", "cancel queued synthesis");
        engine.release();

        assertTrue(result.accepted());
        assertEquals(1, result.affectedSessions());
        assertTrue(failed.await(2L, TimeUnit.SECONDS));
        assertEquals(TtsFailureCode.CANCELLED, taskFailure.get().code());
        assertFalse(engine.awaitInvocations(2, 150));
    }

    @Test
    void stopAllCancelsQueuedAndRunningSessions() throws Exception {
        BlockingSynthesisEngine engine = new BlockingSynthesisEngine();
        List<TtsSession> statuses = Collections.synchronizedList(new ArrayList<>());
        TtsRuntime runtime = runtime(engine, statuses);
        prepareRuntime(runtime);

        runtime.submit(request("running"), null, null);
        assertTrue(engine.awaitStarted());
        runtime.submit(request("queued"), null, null);

        TtsControlResult result = runtime.stopAll("stop all");
        engine.release();

        assertTrue(result.accepted());
        assertEquals(2, result.affectedSessions());
        assertTrue(awaitState(statuses, "running", TtsSessionState.CANCELLED));
        assertTrue(awaitState(statuses, "queued", TtsSessionState.CANCELLED));
    }

    @Test
    void stopRequestCancelsAllDerivedStreamSessions() throws Exception {
        BlockingSynthesisEngine engine = new BlockingSynthesisEngine();
        List<TtsSession> statuses = Collections.synchronizedList(new ArrayList<>());
        TtsRuntime runtime = runtime(engine, statuses);
        prepareRuntime(runtime);

        runtime.submit(request("stream-1:part-a"), null, null);
        assertTrue(engine.awaitStarted());
        runtime.submit(request("stream-1:part-b"), null, null);

        TtsControlResult result = runtime.stopRequest("stream-1", "stop stream");
        engine.release();

        assertTrue(result.accepted());
        assertEquals(2, result.affectedSessions());
        assertTrue(awaitState(statuses, "stream-1:part-a", TtsSessionState.CANCELLED));
        assertTrue(awaitState(statuses, "stream-1:part-b", TtsSessionState.CANCELLED));
    }

    @Test
    void stopRequestBlocksLaterChunksFromSameStreamUntilEnd() throws Exception {
        BlockingSynthesisEngine engine = new BlockingSynthesisEngine();
        List<TtsSession> statuses = Collections.synchronizedList(new ArrayList<>());
        TtsRuntime runtime = runtime(engine, statuses);
        prepareRuntime(runtime);

        runtime.submit(request("stream-1:part-a"), null, null);
        assertTrue(engine.awaitStarted());

        TtsControlResult stop = runtime.stopRequest("stream-1", "stop stream");
        TtsOperationResult ignored = runtime.submitStream(chunk("stream-1", "second sentence.", false), null, null);
        TtsOperationResult end = runtime.submitStream(chunk("stream-1", "", true), null, null);
        engine.release();

        assertTrue(stop.accepted());
        assertTrue(ignored.accepted());
        assertTrue(end.accepted());
        assertEquals(1, engine.invocations.get());
        assertTrue(statuses.stream().anyMatch(session ->
                session.request().requestId().startsWith("stream-1:")
                        && session.state() == TtsSessionState.CANCELLED));
    }

    @Test
    void stopCurrentCancelsOnlyActiveSessionAndKeepsQueue() throws Exception {
        BlockingSynthesisEngine engine = new BlockingSynthesisEngine();
        List<TtsSession> statuses = Collections.synchronizedList(new ArrayList<>());
        TtsRuntime runtime = runtime(engine, statuses);
        prepareRuntime(runtime);

        runtime.submit(request("running"), null, null);
        assertTrue(engine.awaitStarted());
        runtime.submit(request("queued"), null, null);

        TtsControlResult result = runtime.stopCurrent("stop current");
        engine.release();

        assertTrue(result.accepted());
        assertEquals(TtsControlAction.STOP_CURRENT, result.action());
        assertEquals(1, result.affectedSessions());
        assertTrue(awaitState(statuses, "running", TtsSessionState.CANCELLED));
        assertTrue(engine.awaitInvocations(2));
        assertTrue(awaitState(statuses, "queued", TtsSessionState.COMPLETED));
    }

    @Test
    void synthesisFailureMarksSessionFailedAndCallsFailureHandler() throws Exception {
        FailingSynthesisEngine engine = new FailingSynthesisEngine();
        List<TtsSession> statuses = Collections.synchronizedList(new ArrayList<>());
        TtsRuntime runtime = runtime(engine, statuses);
        prepareRuntime(runtime);
        AtomicReference<TtsFailure> failureRef = new AtomicReference<>();

        TtsOperationResult result = runtime.submit(request("failing"), null, failureRef::set);

        assertTrue(result.accepted());
        assertTrue(awaitState(statuses, "failing", TtsSessionState.FAILED));
        assertEquals(TtsFailureCode.SYNTHESIS_FAILED, failureRef.get().code());
    }

    private static void prepareRuntime(TtsRuntime runtime) {
        CountDownLatch prepared = new CountDownLatch(1);
        TtsOperationResult result = runtime.prepare(initialized -> prepared.countDown());
        assertTrue(result.accepted());
        try {
            assertTrue(prepared.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while preparing TTS runtime", exception);
        }
    }

    private TtsRuntime runtime(TtsSynthesisEngine engine, List<TtsSession> statuses) {
        return new TtsRuntime(new FakeGameEnvironment(), executorManager, engine, new FakeAudioBridge(), statuses::add, ignored -> {});
    }

    private static TtsRequest request(String requestId) {
        return new TtsRequest(requestId, requestId, requestId, requestId, "hello", TtsRequestSource.AX, TtsPlaybackPolicy.QUEUE, Priority.NORMAL, TtsVoiceProfile.defaults(), false);
    }

    private static TtsStreamChunk chunk(String streamId, String text, boolean last) {
        return new TtsStreamChunk(streamId, streamId, streamId, text, TtsRequestSource.AX, TtsPlaybackPolicy.QUEUE, TtsVoiceProfile.defaults(), last);
    }

    private static boolean awaitState(List<TtsSession> statuses, String requestId, TtsSessionState state) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000L;
        while (System.currentTimeMillis() < deadline) {
            synchronized (statuses) {
                if (statuses.stream().anyMatch(session -> session.request().requestId().equals(requestId) && session.state() == state)) {
                    return true;
                }
            }
            Thread.sleep(10L);
        }
        return false;
    }

    private static class CountingSynthesisEngine implements TtsSynthesisEngine {
        protected final AtomicInteger invocations = new AtomicInteger();
        private final AtomicReference<String> lastText = new AtomicReference<>("");

        @Override
        public boolean initialize() {
            return true;
        }

        @Override
        public boolean isInitialized() {
            return true;
        }

        @Override
        public boolean isAutoregressive() {
            return false;
        }

        @Override
        public int sampleRate() {
            return 24000;
        }

        @Override
        public TtsBackendSnapshot backendSnapshot() {
            return TtsBackendSnapshot.unavailable();
        }

        @Override
        public boolean useModel(String modelName) {
            return true;
        }

        @Override
        public void synthesize(TtsRequest request, com.rheinmetal.tianshu.function.tts.synthesis.TtsAudioSink sink) {
            lastText.set(request.text());
            invocations.incrementAndGet();
            sink.accept(new byte[]{1, 2});
        }

        @Override
        public void interrupt() {
        }

        @Override
        public void shutdown() {
        }

        boolean awaitInvocations(int expected) throws InterruptedException {
            return awaitInvocations(expected, 2000L);
        }

        boolean awaitInvocations(int expected, long timeoutMillis) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                if (invocations.get() >= expected) {
                    return true;
                }
                Thread.sleep(10L);
            }
            return invocations.get() >= expected;
        }
    }

    private static final class BlockingSynthesisEngine extends CountingSynthesisEngine {
        private final java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);

        @Override
        public void synthesize(TtsRequest request, com.rheinmetal.tianshu.function.tts.synthesis.TtsAudioSink sink) {
            invocations.incrementAndGet();
            started.countDown();
            try {
                release.await(2L, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            sink.accept(new byte[]{1, 2});
        }

        @Override
        public void interrupt() {
            release.countDown();
        }

        boolean awaitStarted() throws InterruptedException {
            return started.await(2L, TimeUnit.SECONDS);
        }

        void release() {
            release.countDown();
        }
    }

    private static final class FailingSynthesisEngine extends CountingSynthesisEngine {
        @Override
        public void synthesize(TtsRequest request, com.rheinmetal.tianshu.function.tts.synthesis.TtsAudioSink sink) {
            invocations.incrementAndGet();
            throw new IllegalStateException("boom");
        }
    }

    private static final class FakeAudioBridge implements IAudioBridge {
        private Runnable callback;

        @Override public void ensureHardwareRunning() {}
        @Override public void releaseCaptureHardware() {}
        @Override public void startRecording() {}
        @Override public byte[] stopRecording() { return new byte[0]; }
        @Override public void startStreamRecording(java.util.function.Consumer<byte[]> onAudioChunk) {}
        @Override public void stopStreamRecording() {}
        @Override public void startTtsPlayback(int sampleRate) {}
        @Override public void feedTtsAudio(byte[] audio) {}
        @Override public void finishTtsPlayback() { Runnable current = callback; callback = null; if (current != null) { current.run(); } }
        @Override public void setOnPlaybackFinished(Runnable callback) { this.callback = callback; }
        @Override public void stopTtsPlayback() { callback = null; }
        @Override public void playAudio(byte[] audioData, int sampleRate) {}
        @Override public void stopPlayback() {}
        @Override public boolean isRecording() { return false; }
        @Override public boolean isPlaying() { return false; }
        @Override public boolean isStreaming() { return false; }
        @Override public List<String> getAvailableMicNames() { return List.of(); }
        @Override public String getCurrentMicName() { return ""; }
        @Override public void selectMic(String micName) {}
        @Override public void switchToNextMic() {}
        @Override public void shutdown() {}
    }

    private static final class FakeGameEnvironment implements IGameEnvironment {
        @Override public void displayMessageToPlayer(String message) {}
        @Override public void executeOnMainThread(Runnable task) { task.run(); }
        @Override public Path getGameDirectory() { return Path.of("."); }
        @Override public boolean isClientSide() { return true; }
        @Override public void openFolder(Path dir) {}
        @Override public void info(String msg) {}
        @Override public void warn(String msg) {}
        @Override public void error(String msg, Throwable t) {}
        @Override public com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink diagnostics() { return com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink.NOOP; }
    }
}
