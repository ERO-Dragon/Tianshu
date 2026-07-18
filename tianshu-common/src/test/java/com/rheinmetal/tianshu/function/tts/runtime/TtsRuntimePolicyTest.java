package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsSynthesisEngine;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.payload.TtsPlaybackState;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutorManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsRuntimePolicyTest {
    private final ProtocolExecutorManager executorManager = new ProtocolExecutorManager(Runnable::run);

    @AfterEach
    void closeExecutor() {
        executorManager.close();
    }

    @Test
    void dropIfBusyAcceptsWithoutCreatingSecondSession() throws Exception {
        BlockingSynthesisEngine engine = new BlockingSynthesisEngine();
        FakeAudioBridge audioBridge = new FakeAudioBridge();
        List<TtsSession> statuses = Collections.synchronizedList(new ArrayList<>());
        TtsRuntime runtime = runtime(engine, audioBridge, statuses);
        prepareRuntime(runtime);

        runtime.submit(request("first", TtsPlaybackPolicy.QUEUE, Priority.NORMAL), null, null);
        assertTrue(engine.awaitStarted());
        TtsOperationResult result = runtime.submit(request("dropped", TtsPlaybackPolicy.DROP_IF_BUSY, Priority.NORMAL), null, null);

        assertTrue(result.accepted());
        assertEquals("dropped", result.requestId());
        assertTrue(statuses.stream().noneMatch(session -> session.request().requestId().equals("dropped")));
        runtime.stopAll("test stop");
        engine.release();
    }

    @Test
    void replaceCurrentCancelsExistingSessionAndRunsReplacement() throws Exception {
        BlockingSynthesisEngine engine = new BlockingSynthesisEngine();
        FakeAudioBridge audioBridge = new FakeAudioBridge();
        List<TtsSession> statuses = Collections.synchronizedList(new ArrayList<>());
        TtsRuntime runtime = runtime(engine, audioBridge, statuses);
        prepareRuntime(runtime);

        runtime.submit(request("first", TtsPlaybackPolicy.QUEUE, Priority.NORMAL), null, null);
        assertTrue(engine.awaitStarted());
        runtime.submit(request("replacement", TtsPlaybackPolicy.REPLACE_CURRENT, Priority.NORMAL), null, null);
        TtsSession first = awaitSession(statuses, "first", TtsSessionState.CANCELLED);
        engine.release();
        assertTrue(engine.awaitInvocationCount(2));
        audioBridge.finishCallback();

        assertEquals(TtsSessionState.CANCELLED, first.state());
        assertTrue(statuses.stream().anyMatch(session -> session.request().requestId().equals("replacement")));
    }

    @Test
    void latestOnlyCancelsPreviousNonTerminalSessions() throws Exception {
        BlockingSynthesisEngine engine = new BlockingSynthesisEngine();
        FakeAudioBridge audioBridge = new FakeAudioBridge();
        List<TtsSession> statuses = Collections.synchronizedList(new ArrayList<>());
        TtsRuntime runtime = runtime(engine, audioBridge, statuses);
        prepareRuntime(runtime);

        runtime.submit(request("first", TtsPlaybackPolicy.QUEUE, Priority.NORMAL), null, null);
        assertTrue(engine.awaitStarted());
        runtime.submit(request("latest", TtsPlaybackPolicy.LATEST_ONLY, Priority.NORMAL), null, null);
        TtsSession first = awaitSession(statuses, "first", TtsSessionState.CANCELLED);
        engine.release();
        assertTrue(engine.awaitInvocationCount(2));

        assertEquals(TtsSessionState.CANCELLED, first.state());
        assertTrue(statuses.stream().anyMatch(session -> session.request().requestId().equals("latest")));
    }

    @Test
    void cancelSentenceAndPlayCancelsActiveRegardlessOfPriority() throws Exception {
        BlockingSynthesisEngine engine = new BlockingSynthesisEngine();
        FakeAudioBridge audioBridge = new FakeAudioBridge();
        List<TtsSession> statuses = Collections.synchronizedList(new ArrayList<>());
        TtsRuntime runtime = runtime(engine, audioBridge, statuses);
        prepareRuntime(runtime);

        runtime.submit(request("low", TtsPlaybackPolicy.QUEUE, Priority.LOW), null, null);
        assertTrue(engine.awaitStarted());
        runtime.submit(request("incoming", TtsPlaybackPolicy.CANCEL_SENTENCE_AND_PLAY, Priority.LOW), null, null);
        TtsSession low = awaitSession(statuses, "low", TtsSessionState.CANCELLED);
        engine.release();
        assertTrue(engine.awaitInvocationCount(2));

        assertEquals(TtsSessionState.CANCELLED, low.state());
        assertTrue(statuses.stream().anyMatch(session -> session.request().requestId().equals("incoming")));
    }

    @Test
    void queuedHighPriorityRequestDoesNotCancelActiveSpeechButRunsBeforeNormalQueue() throws Exception {
        BlockingSynthesisEngine engine = new BlockingSynthesisEngine();
        FakeAudioBridge audioBridge = new FakeAudioBridge();
        List<TtsSession> statuses = Collections.synchronizedList(new ArrayList<>());
        TtsRuntime runtime = runtime(engine, audioBridge, statuses);
        prepareRuntime(runtime);

        runtime.submit(request("running", TtsRequestSource.of("module.ax"), TtsPlaybackPolicy.QUEUE, Priority.LOW), null, null);
        assertTrue(engine.awaitStarted());
        runtime.submit(request("queued", TtsRequestSource.of("module.ax"), TtsPlaybackPolicy.QUEUE, Priority.LOW), null, null);
        runtime.submit(request("urgent-queued", TtsRequestSource.SYSTEM, TtsPlaybackPolicy.QUEUE, Priority.HIGH), null, null);

        assertFalse(awaitOptionalState(statuses, "running", TtsSessionState.CANCELLED, 150));
        engine.release();
        assertTrue(engine.awaitInvocationCount(3));

        assertEquals(List.of("running", "urgent-queued", "queued"), engine.invokedRequestIds());
        assertTrue(statuses.stream().noneMatch(session -> session.request().requestId().equals("running") && session.state() == TtsSessionState.CANCELLED));
    }

    @Test
    void cancelSentenceAndPlayOnlyCancelsActiveSpeechAndPreservesQueuedSpeech() throws Exception {
        BlockingSynthesisEngine engine = new BlockingSynthesisEngine();
        FakeAudioBridge audioBridge = new FakeAudioBridge();
        List<TtsSession> statuses = Collections.synchronizedList(new ArrayList<>());
        List<TtsPlaybackState> playbackStates = Collections.synchronizedList(new ArrayList<>());
        TtsRuntime runtime = runtime(engine, audioBridge, statuses, playbackStates);
        prepareRuntime(runtime);

        runtime.submit(request("speak-1", TtsRequestSource.of("module.ax"), TtsPlaybackPolicy.QUEUE, Priority.LOW), null, null);
        assertTrue(engine.awaitStarted());
        runtime.submit(request("speak-2", TtsRequestSource.of("module.ax"), TtsPlaybackPolicy.QUEUE, Priority.LOW), null, null);
        runtime.submit(request("interrupt", TtsRequestSource.SYSTEM, TtsPlaybackPolicy.CANCEL_SENTENCE_AND_PLAY, Priority.HIGH), null, null);
        TtsSession interrupted = awaitSession(statuses, "speak-1", TtsSessionState.CANCELLED);

        assertEquals(TtsSessionState.CANCELLED, interrupted.state());
        assertTrue(engine.awaitInvocationCount(3));
        assertEquals(List.of("speak-1", "interrupt", "speak-2"), engine.invokedRequestIds());
        assertTrue(statuses.stream().noneMatch(session -> session.request().requestId().equals("speak-2") && session.state() == TtsSessionState.CANCELLED));
        assertTrue(playbackStates.contains(TtsPlaybackState.SPEAKING));
        assertTrue(playbackStates.contains(TtsPlaybackState.ALERTING));
        assertEquals(TtsPlaybackState.IDLE, playbackStates.get(playbackStates.size() - 1));
    }

    @Test
    void queueKeepsSecondSessionUntilFirstCompletes() throws Exception {
        BlockingSynthesisEngine engine = new BlockingSynthesisEngine();
        FakeAudioBridge audioBridge = new FakeAudioBridge();
        List<TtsSession> statuses = Collections.synchronizedList(new ArrayList<>());
        TtsRuntime runtime = runtime(engine, audioBridge, statuses);
        prepareRuntime(runtime);

        runtime.submit(request("first", TtsPlaybackPolicy.QUEUE, Priority.NORMAL), null, null);
        assertTrue(engine.awaitStarted());
        runtime.submit(request("second", TtsPlaybackPolicy.QUEUE, Priority.NORMAL), null, null);

        assertFalse(engine.awaitInvocationCount(2, 150));
        engine.release();
        assertTrue(engine.awaitInvocationCount(2));
        assertTrue(statuses.stream().anyMatch(session -> session.request().requestId().equals("second")));
    }

    @Test
    void submitRejectsWhenInternalSynthesisQueueIsFull() throws Exception {
        BlockingSynthesisEngine engine = new BlockingSynthesisEngine();
        FakeAudioBridge audioBridge = new FakeAudioBridge();
        List<TtsSession> statuses = Collections.synchronizedList(new ArrayList<>());
        TtsRuntime runtime = runtime(engine, audioBridge, statuses);
        prepareRuntime(runtime);
        AtomicReference<TtsFailure> failureRef = new AtomicReference<>();

        runtime.submit(request("running", TtsPlaybackPolicy.QUEUE, Priority.NORMAL), null, null);
        assertTrue(engine.awaitStarted());
        for (int i = 0; i < 8; i++) {
            assertTrue(runtime.submit(request("queued-" + i, TtsPlaybackPolicy.QUEUE, Priority.NORMAL), null, null).accepted());
        }
        TtsOperationResult result = runtime.submit(request("overflow", TtsPlaybackPolicy.QUEUE, Priority.NORMAL), null, failureRef::set);

        assertFalse(result.accepted());
        assertEquals(TtsFailureCode.QUEUE_FULL, result.failure().code());
        assertEquals(TtsFailureCode.QUEUE_FULL, failureRef.get().code());
        runtime.stopAll("test stop");
        engine.release();
    }

    @Test
    void queuedSessionsUseRequestPriorityBeforeFifoOrder() throws Exception {
        BlockingSynthesisEngine engine = new BlockingSynthesisEngine();
        FakeAudioBridge audioBridge = new FakeAudioBridge();
        List<TtsSession> statuses = Collections.synchronizedList(new ArrayList<>());
        TtsRuntime runtime = runtime(engine, audioBridge, statuses);
        prepareRuntime(runtime);

        runtime.submit(request("running", TtsPlaybackPolicy.QUEUE, Priority.NORMAL), null, null);
        assertTrue(engine.awaitStarted());
        runtime.submit(request("low", TtsPlaybackPolicy.QUEUE, Priority.LOW), null, null);
        runtime.submit(request("high", TtsPlaybackPolicy.QUEUE, Priority.HIGH), null, null);

        engine.release();
        assertTrue(engine.awaitInvocationCount(3));

        assertEquals(List.of("running", "high", "low"), engine.invokedRequestIds());
    }

    @Test
    void cancelSessionAndPlayCancelsActiveGroupAndKeepsOtherQueuedSessions() throws Exception {
        BlockingSynthesisEngine engine = new BlockingSynthesisEngine();
        FakeAudioBridge audioBridge = new FakeAudioBridge();
        List<TtsSession> statuses = Collections.synchronizedList(new ArrayList<>());
        TtsRuntime runtime = runtime(engine, audioBridge, statuses);
        prepareRuntime(runtime);

        runtime.submit(request("speak-1", TtsRequestSource.of("module.ax"), TtsPlaybackPolicy.QUEUE, Priority.LOW), null, null);
        assertTrue(engine.awaitStarted());
        runtime.submit(request("speak-2", TtsRequestSource.of("module.ax"), TtsPlaybackPolicy.QUEUE, Priority.LOW), null, null);
        runtime.submit(request("other-queued", TtsRequestSource.SYSTEM, TtsPlaybackPolicy.QUEUE, Priority.LOW), null, null);
        runtime.submit(request("session-replacement", TtsRequestSource.SYSTEM, TtsPlaybackPolicy.CANCEL_SESSION_AND_PLAY, Priority.HIGH), null, null);

        assertTrue(awaitOptionalState(statuses, "speak-1", TtsSessionState.CANCELLED, 500));
        assertFalse(awaitOptionalState(statuses, "speak-2", TtsSessionState.CANCELLED, 150));
        engine.release();
        assertTrue(engine.awaitInvocationCount(4));

        assertEquals(List.of("speak-1", "session-replacement", "speak-2", "other-queued"), engine.invokedRequestIds());
        assertTrue(statuses.stream().noneMatch(session -> session.request().requestId().equals("other-queued") && session.state() == TtsSessionState.CANCELLED));
    }

    @Test
    void insertAfterSessionUsesRuntimePlacementPriority() throws Exception {
        BlockingSynthesisEngine engine = new BlockingSynthesisEngine();
        FakeAudioBridge audioBridge = new FakeAudioBridge();
        List<TtsSession> statuses = Collections.synchronizedList(new ArrayList<>());
        TtsRuntime runtime = runtime(engine, audioBridge, statuses);
        prepareRuntime(runtime);

        runtime.submit(request("running", TtsRequestSource.of("module.ax"), TtsPlaybackPolicy.QUEUE, Priority.NORMAL), null, null);
        assertTrue(engine.awaitStarted());
        runtime.submit(request("queued", TtsRequestSource.of("module.ax"), TtsPlaybackPolicy.QUEUE, Priority.NORMAL), null, null);
        runtime.submit(request("inserted", TtsRequestSource.SYSTEM, TtsPlaybackPolicy.INSERT_AFTER_SESSION, Priority.LOW), null, null);

        engine.release();
        assertTrue(engine.awaitInvocationCount(3));

        assertEquals(List.of("running", "inserted", "queued"), engine.invokedRequestIds());
        assertTrue(statuses.stream().noneMatch(session -> session.request().requestId().equals("running") && session.state() == TtsSessionState.CANCELLED));
    }

    @Test
    void insertAfterSentenceWaitsForCurrentSentenceWithoutPausingSynthesis() throws Exception {
        ChunkedSynthesisEngine engine = new ChunkedSynthesisEngine();
        FakeAudioBridge audioBridge = new FakeAudioBridge();
        List<TtsSession> statuses = Collections.synchronizedList(new ArrayList<>());
        TtsRuntime runtime = runtime(engine, audioBridge, statuses);
        prepareRuntime(runtime);

        runtime.submit(request("running", TtsRequestSource.of("module.ax"), TtsPlaybackPolicy.QUEUE, Priority.NORMAL), null, null);
        assertTrue(engine.awaitFirstChunk("running"));
        TtsOperationResult result = runtime.submit(request("insert-after-sentence", TtsRequestSource.SYSTEM, TtsPlaybackPolicy.INSERT_AFTER_SENTENCE, Priority.HIGH), null, null);

        assertTrue(result.accepted());
        assertFalse(awaitOptionalState(statuses, "running", TtsSessionState.CANCELLED, 150));
        assertFalse(engine.awaitInvocationCount(2, 150));
        engine.releaseRemainder();
        assertTrue(engine.awaitInvocationCount(2));
        audioBridge.finishCallback();
        assertTrue(awaitOptionalState(statuses, "running", TtsSessionState.COMPLETED, 500));
        assertEquals(List.of(1, 2, 9), audioBridge.playedMarkers());
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

    private TtsRuntime runtime(TtsSynthesisEngine engine, FakeAudioBridge audioBridge, List<TtsSession> statuses) {
        return runtime(engine, audioBridge, statuses, new ArrayList<>());
    }

    private TtsRuntime runtime(TtsSynthesisEngine engine, FakeAudioBridge audioBridge, List<TtsSession> statuses, List<TtsPlaybackState> playbackStates) {
        return new TtsRuntime(new FakeGameEnvironment(), executorManager, engine, audioBridge, statuses::add, playbackStates::add);
    }

    private static TtsRequest request(String requestId, TtsPlaybackPolicy policy, Priority priority) {
        return request(requestId, TtsRequestSource.of("module.ax"), policy, priority);
    }

    private static TtsRequest request(String requestId, TtsRequestSource source, TtsPlaybackPolicy policy, Priority priority) {
        return new TtsRequest(
                requestId,
                groupId(requestId),
                requestId,
                requestId,
                "hello " + requestId,
                source,
                policy,
                priority,
                TtsVoiceProfile.defaults()
        );
    }

    private static String groupId(String requestId) {
        if (requestId.startsWith("speak-") || requestId.equals("interrupt")) {
            return "chat-session";
        }
        return requestId;
    }

    private static TtsSession awaitSession(List<TtsSession> statuses, String requestId, TtsSessionState state) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000L;
        while (System.currentTimeMillis() < deadline) {
            synchronized (statuses) {
                for (TtsSession session : statuses) {
                    if (session.request().requestId().equals(requestId) && session.state() == state) {
                        return session;
                    }
                }
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("TTS session state not observed: " + requestId + " -> " + state);
    }

    private static boolean awaitOptionalState(List<TtsSession> statuses, String requestId, TtsSessionState state, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
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

    private static final class BlockingSynthesisEngine implements TtsSynthesisEngine {
        private final CountDownLatch firstStarted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger invocations = new AtomicInteger();
        private final List<String> invokedRequestIds = Collections.synchronizedList(new ArrayList<>());

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
            invokedRequestIds.add(request.requestId());
            invocations.incrementAndGet();
            firstStarted.countDown();
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

        @Override
        public void shutdown() {
            release.countDown();
        }

        boolean awaitStarted() throws InterruptedException {
            return firstStarted.await(2L, TimeUnit.SECONDS);
        }

        boolean awaitInvocationCount(int expected) throws InterruptedException {
            return awaitInvocationCount(expected, 2000L);
        }

        boolean awaitInvocationCount(int expected, long timeoutMillis) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                if (invocations.get() >= expected) {
                    return true;
                }
                Thread.sleep(10L);
            }
            return invocations.get() >= expected;
        }

        void release() {
            release.countDown();
        }

        List<String> invokedRequestIds() {
            synchronized (invokedRequestIds) {
                return List.copyOf(invokedRequestIds);
            }
        }
    }

    private static final class ChunkedSynthesisEngine implements TtsSynthesisEngine {
        private final CountDownLatch runningFirstChunk = new CountDownLatch(1);
        private final CountDownLatch releaseRemainder = new CountDownLatch(1);
        private final AtomicInteger invocations = new AtomicInteger();
        private final List<String> invokedRequestIds = Collections.synchronizedList(new ArrayList<>());

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
            invokedRequestIds.add(request.requestId());
            invocations.incrementAndGet();
            if (request.requestId().equals("running")) {
                sink.accept(new byte[]{1});
                runningFirstChunk.countDown();
                try {
                    releaseRemainder.await(2L, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                sink.accept(new byte[]{2});
                return;
            }
            sink.accept(new byte[]{9});
        }

        @Override
        public void interrupt() {
            releaseRemainder.countDown();
        }

        @Override
        public void shutdown() {
            releaseRemainder.countDown();
        }

        boolean awaitFirstChunk(String requestId) throws InterruptedException {
            if (!"running".equals(requestId)) {
                return false;
            }
            return runningFirstChunk.await(2L, TimeUnit.SECONDS);
        }

        boolean awaitInvocationCount(int expected) throws InterruptedException {
            return awaitInvocationCount(expected, 2000L);
        }

        boolean awaitInvocationCount(int expected, long timeoutMillis) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                if (invocations.get() >= expected) {
                    return true;
                }
                Thread.sleep(10L);
            }
            return invocations.get() >= expected;
        }

        void releaseRemainder() {
            releaseRemainder.countDown();
        }
    }

    private static final class FakeAudioBridge implements IAudioBridge {
        private Runnable callback;
        private final List<Integer> playedMarkers = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void ensureHardwareRunning() {
        }

        @Override
        public void releaseCaptureHardware() {
        }

        @Override
        public void startRecording() {
        }

        @Override
        public byte[] stopRecording() {
            return new byte[0];
        }

        @Override
        public void startStreamRecording(java.util.function.Consumer<byte[]> onAudioChunk) {
        }

        @Override
        public void stopStreamRecording() {
        }

        @Override
        public void startTtsPlayback(int sampleRate) {
        }

        @Override
        public void feedTtsAudio(byte[] audio) {
            if (audio != null && audio.length > 0) {
                playedMarkers.add((int) audio[0]);
            }
        }

        @Override
        public void finishTtsPlayback() {
            finishCallback();
        }

        @Override
        public void setOnPlaybackFinished(Runnable callback) {
            this.callback = callback;
        }

        @Override
        public void stopTtsPlayback() {
            callback = null;
        }

        @Override
        public void playAudio(byte[] audioData, int sampleRate) {
        }

        @Override
        public void stopPlayback() {
        }

        @Override
        public boolean isRecording() {
            return false;
        }

        @Override
        public boolean isPlaying() {
            return false;
        }

        @Override
        public boolean isStreaming() {
            return false;
        }

        @Override
        public List<String> getAvailableMicNames() {
            return List.of();
        }

        @Override
        public String getCurrentMicName() {
            return "";
        }

        @Override
        public void selectMic(String micName) {
        }

        @Override
        public void switchToNextMic() {
        }

        @Override
        public void shutdown() {
        }

        List<Integer> playedMarkers() {
            synchronized (playedMarkers) {
                return List.copyOf(playedMarkers);
            }
        }

        void finishCallback() {
            Runnable current = callback;
            callback = null;
            if (current != null) {
                current.run();
            }
        }
    }

    private static final class FakeGameEnvironment implements IGameEnvironment {
        @Override
        public void displayMessageToPlayer(String message) {
        }

        @Override
        public void executeOnMainThread(Runnable task) {
            task.run();
        }

        @Override
        public Path getGameDirectory() {
            return Path.of(".");
        }

        @Override
        public boolean isClientSide() {
            return true;
        }

        @Override
        public void openFolder(Path dir) {
        }

        @Override
        public void info(String msg) {
        }

        @Override
        public void warn(String msg) {
        }

        @Override
        public void error(String msg, Throwable t) {
        }

        @Override
        public com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink diagnostics() {
            return com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink.NOOP;
        }
    }
}
