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
        runtime.prepare();

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
        runtime.prepare();

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
        runtime.prepare();

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
    void interruptLowerPriorityCancelsWhenIncomingPriorityIsHighEnough() throws Exception {
        BlockingSynthesisEngine engine = new BlockingSynthesisEngine();
        FakeAudioBridge audioBridge = new FakeAudioBridge();
        List<TtsSession> statuses = Collections.synchronizedList(new ArrayList<>());
        TtsRuntime runtime = runtime(engine, audioBridge, statuses);
        runtime.prepare();

        runtime.submit(request("low", TtsPlaybackPolicy.QUEUE, Priority.LOW), null, null);
        assertTrue(engine.awaitStarted());
        runtime.submit(request("high", TtsPlaybackPolicy.INTERRUPT_LOWER_PRIORITY, Priority.HIGH), null, null);
        TtsSession low = awaitSession(statuses, "low", TtsSessionState.CANCELLED);
        engine.release();
        assertTrue(engine.awaitInvocationCount(2));

        assertEquals(TtsSessionState.CANCELLED, low.state());
        assertTrue(statuses.stream().anyMatch(session -> session.request().requestId().equals("high")));
    }

    @Test
    void alertInterruptsOnlyActiveSpeechAndPreservesQueuedSpeech() throws Exception {
        BlockingSynthesisEngine engine = new BlockingSynthesisEngine();
        FakeAudioBridge audioBridge = new FakeAudioBridge();
        List<TtsSession> statuses = Collections.synchronizedList(new ArrayList<>());
        List<TtsPlaybackState> playbackStates = Collections.synchronizedList(new ArrayList<>());
        TtsRuntime runtime = runtime(engine, audioBridge, statuses, playbackStates);
        runtime.prepare();

        runtime.submit(request("speak-1", TtsRequestSource.AX, TtsPlaybackPolicy.QUEUE, Priority.LOW), null, null);
        assertTrue(engine.awaitStarted());
        runtime.submit(request("speak-2", TtsRequestSource.AX, TtsPlaybackPolicy.QUEUE, Priority.LOW), null, null);
        runtime.submit(request("alert", TtsRequestSource.ALERT, TtsPlaybackPolicy.INTERRUPT_LOWER_PRIORITY, Priority.HIGH), null, null);
        TtsSession interrupted = awaitSession(statuses, "speak-1", TtsSessionState.CANCELLED);

        assertEquals(TtsSessionState.CANCELLED, interrupted.state());
        assertTrue(engine.awaitInvocationCount(3));
        assertEquals(List.of("speak-1", "alert", "speak-2"), engine.invokedRequestIds());
        assertTrue(statuses.stream().noneMatch(session -> session.request().requestId().equals("speak-2") && session.state() == TtsSessionState.CANCELLED));
        assertTrue(playbackStates.contains(TtsPlaybackState.SPEAKING));
        assertTrue(playbackStates.contains(TtsPlaybackState.ALERTING));
    }

    @Test
    void queueKeepsSecondSessionUntilFirstCompletes() throws Exception {
        BlockingSynthesisEngine engine = new BlockingSynthesisEngine();
        FakeAudioBridge audioBridge = new FakeAudioBridge();
        List<TtsSession> statuses = Collections.synchronizedList(new ArrayList<>());
        TtsRuntime runtime = runtime(engine, audioBridge, statuses);
        runtime.prepare();

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
        runtime.prepare();
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
        assertTrue(awaitSession(statuses, "overflow", TtsSessionState.FAILED) != null);
        runtime.stopAll("test stop");
        engine.release();
    }

    @Test
    void queuedSessionsUseRequestPriorityBeforeFifoOrder() throws Exception {
        BlockingSynthesisEngine engine = new BlockingSynthesisEngine();
        FakeAudioBridge audioBridge = new FakeAudioBridge();
        List<TtsSession> statuses = Collections.synchronizedList(new ArrayList<>());
        TtsRuntime runtime = runtime(engine, audioBridge, statuses);
        runtime.prepare();

        runtime.submit(request("running", TtsPlaybackPolicy.QUEUE, Priority.NORMAL), null, null);
        assertTrue(engine.awaitStarted());
        runtime.submit(request("low", TtsPlaybackPolicy.QUEUE, Priority.LOW), null, null);
        runtime.submit(request("high", TtsPlaybackPolicy.QUEUE, Priority.HIGH), null, null);

        engine.release();
        assertTrue(engine.awaitInvocationCount(3));

        assertEquals(List.of("running", "high", "low"), engine.invokedRequestIds());
    }

    private TtsRuntime runtime(BlockingSynthesisEngine engine, FakeAudioBridge audioBridge, List<TtsSession> statuses) {
        return runtime(engine, audioBridge, statuses, new ArrayList<>());
    }

    private TtsRuntime runtime(BlockingSynthesisEngine engine, FakeAudioBridge audioBridge, List<TtsSession> statuses, List<TtsPlaybackState> playbackStates) {
        return new TtsRuntime(new FakeGameEnvironment(), executorManager, engine, audioBridge, statuses::add, playbackStates::add);
    }

    private static TtsRequest request(String requestId, TtsPlaybackPolicy policy, Priority priority) {
        return request(requestId, TtsRequestSource.AX, policy, priority);
    }

    private static TtsRequest request(String requestId, TtsRequestSource source, TtsPlaybackPolicy policy, Priority priority) {
        return new TtsRequest(
                requestId,
                requestId,
                requestId,
                "hello " + requestId,
                source,
                policy,
                priority,
                TtsVoiceProfile.defaults(),
                false
        );
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

    private static final class FakeAudioBridge implements IAudioBridge {
        private Runnable callback;

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
    }
}
