package com.rheinmetal.tianshu.function.tts;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.runtime.ModuleRuntimeState;
import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import com.rheinmetal.tianshu.function.tts.runtime.TtsRuntime;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.voice.VoiceResourceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

class TtsModuleLifecycleBoundaryTest {
    @TempDir
    Path tempDir;

    @Test
    void stopCancelsDelayedAutoLoadBeforeItCanRestartRuntime() throws Exception {
        IGameEnvironment env = new TestLlmSupport.FakeGameEnvironment();
        TestLlmSupport.FakeConfig config = new TestLlmSupport.FakeConfig(tempDir)
                .ttsAutoLoadDelayMillis(100L);
        ProtocolRuntime protocolRuntime = new ProtocolRuntime(Runnable::run);
        ModuleServiceRegistry services = new ModuleServiceRegistry();
        TtsModule module = new TtsModule(new NoopAudioBridge(), protocolRuntime, env, config);
        module.register(new ModuleRegistrationContext(protocolRuntime, services));
        module.prepare(new ModuleRuntimeContext(
                protocolRuntime,
                services,
                new VoiceResourceManager(env, config),
                new ModuleRuntimeState()
        ));

        module.start(null);
        module.stop();
        Thread.sleep(180L);

        assertFalse(services.require(TtsRuntime.class).snapshot().running());
        module.destroy();
        protocolRuntime.close();
    }

    private static final class NoopAudioBridge implements IAudioBridge {
        @Override public void ensureHardwareRunning() { }
        @Override public void releaseCaptureHardware() { }
        @Override public void startRecording() { }
        @Override public byte[] stopRecording() { return new byte[0]; }
        @Override public void startStreamRecording(java.util.function.Consumer<byte[]> onAudioChunk) { }
        @Override public void stopStreamRecording() { }
        @Override public void startTtsPlayback(int sampleRate) { }
        @Override public void feedTtsAudio(byte[] audio) { }
        @Override public void finishTtsPlayback() { }
        @Override public void setOnPlaybackFinished(Runnable callback) { }
        @Override public void stopTtsPlayback() { }
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
