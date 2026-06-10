package com.rheinmetal.tianshu.function.asr.audio;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AudioCaptureServiceTest {
    @Test
    void pttCaptureDoesNotPublishSpeechActivity() {
        FakeAudioBridge bridge = new FakeAudioBridge();
        List<Boolean> states = new ArrayList<>();
        AudioCaptureService service = new AudioCaptureService(bridge, new FakeGameEnvironment(), (speaking, sessionId, occurredAtMillis) -> states.add(speaking));

        service.startPttCapture(42L);
        bridge.push(pcm(0.03D, 160));
        service.stopPttCapture();

        assertEquals(List.of(), states);
    }

    @Test
    void streamCapturePublishesSpeechActivity() {
        FakeAudioBridge bridge = new FakeAudioBridge();
        List<Boolean> states = new ArrayList<>();
        AudioCaptureService service = new AudioCaptureService(bridge, new FakeGameEnvironment(), (speaking, sessionId, occurredAtMillis) -> states.add(speaking));

        service.startStreamCapture(42L, ignored -> {});
        bridge.push(pcm(0.03D, 160));
        repeat(45, () -> bridge.push(pcm(0.0D, 160)));

        assertEquals(List.of(true, false), states);
    }

    private static void repeat(int count, Runnable runnable) {
        for (int i = 0; i < count; i++) {
            runnable.run();
        }
    }

    private static byte[] pcm(double amplitude, int samples) {
        byte[] audio = new byte[samples * 2];
        short value = (short) Math.round(Math.max(-1.0D, Math.min(1.0D, amplitude)) * Short.MAX_VALUE);
        for (int index = 0; index < audio.length; index += 2) {
            audio[index] = (byte) (value & 0xFF);
            audio[index + 1] = (byte) ((value >>> 8) & 0xFF);
        }
        return audio;
    }

    private static final class FakeAudioBridge implements IAudioBridge {
        private Consumer<byte[]> streamConsumer;

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
        public void startStreamRecording(Consumer<byte[]> onAudioChunk) {
            streamConsumer = onAudioChunk;
        }

        @Override
        public void stopStreamRecording() {
            streamConsumer = null;
        }

        @Override
        public void startTtsPlayback(int sampleRate) {
        }

        @Override
        public void feedTtsAudio(byte[] audio) {
        }

        @Override
        public void finishTtsPlayback() {
        }

        @Override
        public void setOnPlaybackFinished(Runnable callback) {
        }

        @Override
        public void stopTtsPlayback() {
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
            return streamConsumer != null;
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

        private void push(byte[] audio) {
            Consumer<byte[]> consumer = streamConsumer;
            if (consumer != null) {
                consumer.accept(audio);
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
