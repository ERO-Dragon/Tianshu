package com.rheinmetal.tianshu.function.tts.playback;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import com.rheinmetal.tianshu.function.tts.runtime.TtsRequest;
import com.rheinmetal.tianshu.function.tts.runtime.TtsRequestSource;
import com.rheinmetal.tianshu.function.tts.runtime.TtsSession;
import com.rheinmetal.tianshu.function.tts.runtime.TtsPlaybackPolicy;
import com.rheinmetal.tianshu.function.tts.runtime.TtsVoiceProfile;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutorManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsPlaybackControllerTest {
    @Test
    void stopAllDispatchesAudioBridgeStopOffCallerThread() throws Exception {
        ProtocolExecutorManager executors = new ProtocolExecutorManager(Runnable::run);
        RecordingAudioBridge audioBridge = new RecordingAudioBridge();
        TtsPlaybackController controller = new TtsPlaybackController(
                audioBridge,
                new TestLlmSupport.FakeGameEnvironment(),
                ignored -> { },
                executors
        );
        String callerThread = Thread.currentThread().getName();

        try {
            controller.stopAll("test stop");

            assertTrue(audioBridge.stopCalled.await(2, TimeUnit.SECONDS));
            assertNotEquals(callerThread, audioBridge.stopThread.get());
        } finally {
            executors.close();
        }
    }

    @Test
    void previewWaitsForPcmPrebufferBeforeStarting() throws Exception {
        ProtocolExecutorManager executors = new ProtocolExecutorManager(Runnable::run);
        RecordingAudioBridge audioBridge = new RecordingAudioBridge();
        TtsPlaybackController controller = new TtsPlaybackController(
                audioBridge,
                new TestLlmSupport.FakeGameEnvironment(),
                ignored -> { },
                executors
        );
        TtsSession session = session("preview", TtsRequestSource.PREVIEW);
        try {
            controller.enqueue(session);
            controller.begin(session, 1_000);
            assertTrue(controller.feed(session, new byte[100]));
            assertEquals(0, audioBridge.startCount);
            assertTrue(controller.feed(session, new byte[1_180]));
            assertTrue(audioBridge.startCalled.await(1, TimeUnit.SECONDS));
            assertEquals(1, audioBridge.startCount);
        } finally {
            executors.close();
        }
    }

    @Test
    void previewFinishStartsShortStreamAndDrainsAudio() throws Exception {
        ProtocolExecutorManager executors = new ProtocolExecutorManager(Runnable::run);
        RecordingAudioBridge audioBridge = new RecordingAudioBridge();
        TtsPlaybackController controller = new TtsPlaybackController(
                audioBridge,
                new TestLlmSupport.FakeGameEnvironment(),
                ignored -> { },
                executors
        );
        TtsSession session = session("short-preview", TtsRequestSource.PREVIEW);
        try {
            controller.enqueue(session);
            controller.begin(session, 1_000);
            assertTrue(controller.feed(session, new byte[20]));
            controller.finish(session);
            assertTrue(audioBridge.finishCalled.await(1, TimeUnit.SECONDS));
            assertEquals(1, audioBridge.startCount);
            assertEquals(1, audioBridge.feedCount);
            assertEquals(1, audioBridge.finishCount);
        } finally {
            executors.close();
        }
    }

    @Test
    void normalSpeechStillStartsAtBegin() throws Exception {
        ProtocolExecutorManager executors = new ProtocolExecutorManager(Runnable::run);
        RecordingAudioBridge audioBridge = new RecordingAudioBridge();
        TtsPlaybackController controller = new TtsPlaybackController(
                audioBridge,
                new TestLlmSupport.FakeGameEnvironment(),
                ignored -> { },
                executors
        );
        TtsSession session = session("normal", TtsRequestSource.SYSTEM);
        try {
            controller.enqueue(session);
            controller.begin(session, 1_000);
            assertTrue(audioBridge.startCalled.await(1, TimeUnit.SECONDS));
            assertEquals(1, audioBridge.startCount);
        } finally {
            executors.close();
        }
    }

    private static TtsSession session(String requestId, TtsRequestSource source) {
        return new TtsSession(new TtsRequest(
                requestId, requestId, requestId, requestId, "test", source,
                TtsPlaybackPolicy.QUEUE, Priority.NORMAL, TtsVoiceProfile.defaults()
        ));
    }

    private static final class RecordingAudioBridge implements IAudioBridge {
        private final CountDownLatch stopCalled = new CountDownLatch(1);
        private final AtomicReference<String> stopThread = new AtomicReference<>();
        private final CountDownLatch startCalled = new CountDownLatch(1);
        private final CountDownLatch finishCalled = new CountDownLatch(1);
        private int startCount;
        private int feedCount;
        private int finishCount;

        @Override public void ensureHardwareRunning() { }
        @Override public void releaseCaptureHardware() { }
        @Override public void startRecording() { }
        @Override public byte[] stopRecording() { return new byte[0]; }
        @Override public void startStreamRecording(Consumer<byte[]> onAudioChunk) { }
        @Override public void stopStreamRecording() { }
        @Override public void startTtsPlayback(int sampleRate) { startCount++; startCalled.countDown(); }
        @Override public void feedTtsAudio(byte[] audio) { feedCount++; }
        @Override public void finishTtsPlayback() { finishCount++; finishCalled.countDown(); }
        @Override public void setOnPlaybackFinished(Runnable callback) { }

        @Override
        public void stopTtsPlayback() {
            stopThread.set(Thread.currentThread().getName());
            stopCalled.countDown();
        }

        @Override public void playAudio(byte[] audioData, int sampleRate) { }
        @Override public void stopPlayback() { }
        @Override public boolean isRecording() { return false; }
        @Override public boolean isPlaying() { return false; }
        @Override public boolean isStreaming() { return false; }
        @Override public List<String> getAvailableMicNames() { return List.of(); }
        @Override public String getCurrentMicName() { return ""; }
        @Override public void selectMic(String micName) { }
        @Override public void switchToNextMic() { }
        @Override public void shutdown() { }
    }
}
