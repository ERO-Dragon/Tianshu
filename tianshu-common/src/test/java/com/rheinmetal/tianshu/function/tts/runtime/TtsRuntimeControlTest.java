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
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
        runtime.prepare();
        AtomicReference<TtsFailure> failureRef = new AtomicReference<>();

        TtsOperationResult result = runtime.submitStream(null, null, failureRef::set);

        assertFalse(result.accepted());
        assertEquals(TtsFailureCode.INVALID_REQUEST, result.failure().code());
        assertEquals(TtsFailureCode.INVALID_REQUEST, failureRef.get().code());
    }

    @Test
    void submitStreamBuffersPartialTextUntilFinalChunk() throws Exception {
        CountingSynthesisEngine engine = new CountingSynthesisEngine();
        List<TtsSession> statuses = new ArrayList<>();
        TtsRuntime runtime = runtime(engine, statuses);
        runtime.prepare();

        TtsOperationResult first = runtime.submitStream(chunk("stream-1", "hello", false), null, null);
        TtsOperationResult last = runtime.submitStream(chunk("stream-1", " world", true), null, null);

        assertTrue(first.accepted());
        assertTrue(last.accepted());
        assertTrue(engine.awaitInvocations(1));
        assertEquals("hello world", engine.lastText.get());
        assertTrue(statuses.stream().anyMatch(session -> session.request().requestId().startsWith("stream-1:")));
    }

    @Test
    void stopAllCancelsQueuedAndRunningSessions() throws Exception {
        BlockingSynthesisEngine engine = new BlockingSynthesisEngine();
        List<TtsSession> statuses = new ArrayList<>();
        TtsRuntime runtime = runtime(engine, statuses);
        runtime.prepare();

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

    private TtsRuntime runtime(TtsSynthesisEngine engine, List<TtsSession> statuses) {
        return new TtsRuntime(new FakeGameEnvironment(), executorManager, engine, new FakeAudioBridge(), statuses::add, ignored -> {});
    }

    private static TtsRequest request(String requestId) {
        return new TtsRequest(requestId, requestId, requestId, "hello", TtsRequestSource.ASSISTANT, TtsPlaybackPolicy.QUEUE, Priority.NORMAL, TtsVoiceProfile.defaults(), false);
    }

    private static TtsStreamChunk chunk(String streamId, String text, boolean last) {
        return new TtsStreamChunk(streamId, streamId, streamId, text, TtsRequestSource.ASSISTANT, TtsPlaybackPolicy.QUEUE, TtsVoiceProfile.defaults(), last);
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
            long deadline = System.currentTimeMillis() + 2000L;
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
    }
}
