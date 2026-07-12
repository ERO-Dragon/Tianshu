package com.rheinmetal.tianshu.core;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TianshuCoreManagerReadinessTest {
    @TempDir
    Path tempDir;

    @Test
    void environmentReadinessFollowsCoreLifecycle() {
        TianshuCoreManager coreManager = new TianshuCoreManager(
                new TestLlmSupport.FakeGameEnvironment(),
                new TestLlmSupport.FakeConfig(tempDir),
                new NoopAudioBridge()
        );

        assertFalse(coreManager.isEnvironmentReady());
        assertFalse(coreManager.isEnvironmentSetupCompleted());

        coreManager.initWorkers();

        assertTrue(coreManager.isEnvironmentReady());
        assertTrue(coreManager.isEnvironmentSetupCompleted());

        coreManager.destroy();

        assertFalse(coreManager.isEnvironmentReady());
        assertFalse(coreManager.isEnvironmentSetupCompleted());
    }

    private static final class NoopAudioBridge implements IAudioBridge {
        @Override public void ensureHardwareRunning() {}
        @Override public void releaseCaptureHardware() {}
        @Override public void startRecording() {}
        @Override public byte[] stopRecording() { return new byte[0]; }
        @Override public void startStreamRecording(Consumer<byte[]> onAudioChunk) {}
        @Override public void stopStreamRecording() {}
        @Override public void startTtsPlayback(int sampleRate) {}
        @Override public void feedTtsAudio(byte[] audio) {}
        @Override public void finishTtsPlayback() {}
        @Override public void setOnPlaybackFinished(Runnable callback) {}
        @Override public void stopTtsPlayback() {}
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
}
