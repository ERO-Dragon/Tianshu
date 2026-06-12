package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsBackendType;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsSynthesisEngine;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsSynthesisMode;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutorManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsRuntimePipelineSmokeTest {
    private final ProtocolExecutorManager executorManager = new ProtocolExecutorManager(Runnable::run);

    @AfterEach
    void closeExecutor() {
        executorManager.close();
    }

    @Test
    void synthesizesNextSessionWhilePreviousSessionIsStillPlaying() throws Exception {
        PipelineEngine engine = new PipelineEngine();
        SlowAudioBridge audioBridge = new SlowAudioBridge();
        TtsRuntime runtime = new TtsRuntime(new FakeGameEnvironment(), executorManager, engine, audioBridge, ignored -> {}, ignored -> {});
        runtime.prepare();

        runtime.submit(request("long-1", "这是一句比较长的回复，用来制造足够长的播放缓冲。"), null, null);
        runtime.submit(request("short-1", "好的。"), null, null);
        runtime.submit(request("long-2", "接下来再补充一句较长的说明，验证长短句交叉时队列依然稳定。"), null, null);
        runtime.submit(request("short-2", "完成。"), null, null);

        assertTrue(engine.awaitInvocations(4), "all sessions should be synthesized");
        assertTrue(audioBridge.awaitFirstFinish(), "first playback should finish");

        long secondStartedAt = engine.startedAt("short-1");
        long firstFinishedAt = audioBridge.firstFinishedAt();
        assertTrue(secondStartedAt > 0L, "second session did not start");
        assertTrue(firstFinishedAt > 0L, "first playback did not finish");
        assertTrue(secondStartedAt < firstFinishedAt, "second synthesis should start before first playback finishes");
        assertEquals(TtsSynthesisMode.STREAMING, engine.modeOf("long-1"));
        assertEquals(TtsSynthesisMode.FULL, engine.modeOf("short-1"));

        runtime.stopAll("test done");
    }

    @Test
    void interruptRequestPreemptsQueuedSpeechAfterCancellingCurrentPlayback() throws Exception {
        PipelineEngine engine = new PipelineEngine();
        SlowAudioBridge audioBridge = new SlowAudioBridge();
        List<TtsSession> statuses = java.util.Collections.synchronizedList(new ArrayList<>());
        TtsRuntime runtime = new TtsRuntime(new FakeGameEnvironment(), executorManager, engine, audioBridge, statuses::add, ignored -> {});
        runtime.prepare();

        runtime.submit(request("long-1", "long playback"), null, null);
        assertTrue(engine.awaitInvocations(1), "first speech should be synthesized");
        assertTrue(audioBridge.awaitFeedCount(1), "first speech should start playback");

        runtime.submit(request("short-1", "queued speech"), null, null);
        assertTrue(engine.awaitInvocations(2), "queued speech should be synthesized before alert arrives");

        runtime.submit(interruptRequest("interrupt", "interrupt speech"), null, null);
        assertTrue(engine.awaitInvocations(3), "interrupt request should be synthesized");
        assertTrue(audioBridge.awaitFeedCount(3), "interrupt and queued speech should be played");

        assertEquals(List.of(1, 9, 2), audioBridge.playedMarkers());
        assertTrue(statuses.stream().anyMatch(session -> session.request().requestId().equals("long-1") && session.state() == TtsSessionState.CANCELLED));
        assertTrue(statuses.stream().noneMatch(session -> session.request().requestId().equals("short-1") && session.state() == TtsSessionState.CANCELLED));

        runtime.stopAll("test done");
    }

    @Test
    void interruptRequestCanInterruptAnotherInterruptRequest() throws Exception {
        PipelineEngine engine = new PipelineEngine();
        SlowAudioBridge audioBridge = new SlowAudioBridge();
        List<TtsSession> statuses = java.util.Collections.synchronizedList(new ArrayList<>());
        TtsRuntime runtime = new TtsRuntime(new FakeGameEnvironment(), executorManager, engine, audioBridge, statuses::add, ignored -> {});
        runtime.prepare();

        runtime.submit(interruptRequest("interrupt-1", "first interrupt"), null, null);
        assertTrue(engine.awaitInvocations(1), "first interrupt request should be synthesized");
        assertTrue(audioBridge.awaitFeedCount(1), "first interrupt request should start playback");

        runtime.submit(interruptRequest("interrupt-2", "second interrupt"), null, null);
        assertTrue(engine.awaitInvocations(2), "second interrupt request should be synthesized");
        assertTrue(audioBridge.awaitFeedCount(2), "second interrupt request should play after interrupting first");

        assertEquals(List.of(9, 8), audioBridge.playedMarkers());
        assertTrue(statuses.stream().anyMatch(session -> session.request().requestId().equals("interrupt-1") && session.state() == TtsSessionState.CANCELLED));
        assertTrue(statuses.stream().noneMatch(session -> session.request().requestId().equals("interrupt-2") && session.state() == TtsSessionState.CANCELLED));

        runtime.stopAll("test done");
    }

    private static TtsRequest request(String requestId, String text) {
        return new TtsRequest(requestId, groupId(requestId), requestId, requestId, text, TtsRequestSource.AX, TtsPlaybackPolicy.QUEUE, Priority.NORMAL, TtsVoiceProfile.defaults(), false);
    }

    private static TtsRequest interruptRequest(String requestId, String text) {
        return new TtsRequest(requestId, requestId, requestId, requestId, text, TtsRequestSource.SYSTEM, TtsPlaybackPolicy.CANCEL_SENTENCE_AND_PLAY, Priority.HIGH, TtsVoiceProfile.defaults(), true);
    }

    private static String groupId(String requestId) {
        return requestId.startsWith("long") || requestId.startsWith("short") ? "chat-session" : requestId;
    }

    private static final class PipelineEngine implements TtsSynthesisEngine {
        private final List<Invocation> invocations = java.util.Collections.synchronizedList(new ArrayList<>());

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
            return true;
        }

        @Override
        public int sampleRate() {
            return 24_000;
        }

        @Override
        public TtsBackendSnapshot backendSnapshot() {
            return new TtsBackendSnapshot(true, true, TtsBackendType.MOSS, "moss", true, sampleRate(), ".", System.currentTimeMillis());
        }

        @Override
        public boolean useModel(String modelName) {
            return true;
        }

        @Override
        public void synthesize(TtsRequest request, com.rheinmetal.tianshu.function.tts.synthesis.TtsAudioSink sink) {
            TtsSynthesisMode mode = sink.preferredSynthesisMode();
            invocations.add(new Invocation(request.requestId(), mode, System.currentTimeMillis()));
            int audioMillis = request.requestId().startsWith("long") || request.requestId().equals("interrupt-1") ? 1_600 : 300;
            byte[] audio = new byte[24_000 * 2 * audioMillis / 1000];
            audio[0] = marker(request.requestId());
            sink.accept(audio);
            sink.reportSynthesisMetrics(new com.rheinmetal.tianshu.function.tts.synthesis.TtsSynthesisMetrics(
                    mode,
                    request.text().length(),
                    audioMillis,
                    mode == TtsSynthesisMode.STREAMING ? 900L : 120L,
                    mode == TtsSynthesisMode.STREAMING ? 220L : 120L
            ));
        }

        private byte marker(String requestId) {
            if (requestId.startsWith("long")) {
                return 1;
            }
            if (requestId.equals("interrupt-2")) {
                return 8;
            }
            if (requestId.startsWith("interrupt")) {
                return 9;
            }
            return 2;
        }

        @Override
        public void interrupt() {
        }

        @Override
        public void shutdown() {
        }

        boolean awaitInvocations(int expected) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 2_000L;
            while (System.currentTimeMillis() < deadline) {
                synchronized (invocations) {
                    if (invocations.size() >= expected) {
                        return true;
                    }
                }
                Thread.sleep(10L);
            }
            synchronized (invocations) {
                return invocations.size() >= expected;
            }
        }

        long startedAt(String requestId) {
            synchronized (invocations) {
                return invocations.stream()
                        .filter(invocation -> invocation.requestId().equals(requestId))
                        .mapToLong(Invocation::startedAtMillis)
                        .findFirst()
                        .orElse(0L);
            }
        }

        TtsSynthesisMode modeOf(String requestId) {
            synchronized (invocations) {
                return invocations.stream()
                        .filter(invocation -> invocation.requestId().equals(requestId))
                        .map(Invocation::mode)
                        .findFirst()
                        .orElse(TtsSynthesisMode.FULL);
            }
        }
    }

    private record Invocation(String requestId, TtsSynthesisMode mode, long startedAtMillis) {
    }

    private static final class SlowAudioBridge implements IAudioBridge {
        private final CountDownLatch firstFinish = new CountDownLatch(1);
        private final List<Integer> playedMarkers = java.util.Collections.synchronizedList(new ArrayList<>());
        private volatile Runnable callback;
        private volatile long firstFinishedAt;

        @Override public void ensureHardwareRunning() {}
        @Override public void releaseCaptureHardware() {}
        @Override public void startRecording() {}
        @Override public byte[] stopRecording() { return new byte[0]; }
        @Override public void startStreamRecording(java.util.function.Consumer<byte[]> onAudioChunk) {}
        @Override public void stopStreamRecording() {}
        @Override public void startTtsPlayback(int sampleRate) {}
        @Override public void feedTtsAudio(byte[] audio) {
            if (audio != null && audio.length > 0) {
                playedMarkers.add((int) audio[0]);
            }
            try {
                Thread.sleep(audio.length > 20_000 ? 450L : 80L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        @Override public void finishTtsPlayback() {
            if (firstFinishedAt == 0L) {
                firstFinishedAt = System.currentTimeMillis();
                firstFinish.countDown();
            }
            Runnable current = callback;
            callback = null;
            if (current != null) {
                current.run();
            }
        }
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

        boolean awaitFirstFinish() throws InterruptedException {
            return firstFinish.await(2L, TimeUnit.SECONDS);
        }

        long firstFinishedAt() {
            return firstFinishedAt;
        }

        boolean awaitFeedCount(int expected) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 2_000L;
            while (System.currentTimeMillis() < deadline) {
                synchronized (playedMarkers) {
                    if (playedMarkers.size() >= expected) {
                        return true;
                    }
                }
                Thread.sleep(10L);
            }
            synchronized (playedMarkers) {
                return playedMarkers.size() >= expected;
            }
        }

        List<Integer> playedMarkers() {
            synchronized (playedMarkers) {
                return List.copyOf(playedMarkers);
            }
        }
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
