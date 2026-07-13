package com.rheinmetal.tianshu.function.tts.playback;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutorManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    private static final class RecordingAudioBridge implements IAudioBridge {
        private final CountDownLatch stopCalled = new CountDownLatch(1);
        private final AtomicReference<String> stopThread = new AtomicReference<>();

        @Override public void ensureHardwareRunning() { }
        @Override public void releaseCaptureHardware() { }
        @Override public void startRecording() { }
        @Override public byte[] stopRecording() { return new byte[0]; }
        @Override public void startStreamRecording(Consumer<byte[]> onAudioChunk) { }
        @Override public void stopStreamRecording() { }
        @Override public void startTtsPlayback(int sampleRate) { }
        @Override public void feedTtsAudio(byte[] audio) { }
        @Override public void finishTtsPlayback() { }
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
