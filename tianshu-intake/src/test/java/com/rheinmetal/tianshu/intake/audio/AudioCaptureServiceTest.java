package com.rheinmetal.tianshu.intake.audio;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.intake.recognition.AsrSpeechSegmenter;
import com.rheinmetal.tianshu.intake.recognition.AsrVadSpeechSegmenter;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AudioCaptureServiceTest {
    @Test
    void pttCaptureDoesNotPublishSpeechActivity() {
        FakeAudioBridge bridge = new FakeAudioBridge();
        List<Boolean> states = new ArrayList<>();
        AudioCaptureService service = new AudioCaptureService(bridge, new FakeGameEnvironment(), (speaking, sessionId, occurredAtMillis) -> states.add(speaking));

        service.startPttCapture();
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
        emitNoise(bridge, 55);
        emitSpeech(bridge, 40);
        emitNoise(bridge, 120);

        assertEquals(List.of(true, false), states);
    }

    @Test
    void streamCaptureForwardsSpeechSegmentBoundariesWithProcessedAudio() {
        FakeAudioBridge bridge = new FakeAudioBridge();
        List<AsrSpeechSegmenter.Decision> decisions = new ArrayList<>();
        AudioCaptureService service = new AudioCaptureService(bridge, new FakeGameEnvironment(),
                new com.rheinmetal.tianshu.intake.recognition.AsrVadSpeechSegmenter((speaking, sessionId, occurredAtMillis) -> {
                }));

        service.startStreamCapture(42L, (chunk, decision) -> decisions.add(decision));
        emitNoise(bridge, 55);
        emitSpeech(bridge, 40);
        emitNoise(bridge, 120);

        assertEquals(1, decisions.stream().filter(AsrSpeechSegmenter.Decision::startsSegment).count());
        assertEquals(1, decisions.stream().filter(AsrSpeechSegmenter.Decision::endsSegment).count());
    }

    @Test
    void streamCaptureDetectsQuietSpeechAfterHighPassProcessing() {
        FakeAudioBridge bridge = new FakeAudioBridge();
        List<Boolean> states = new ArrayList<>();
        AudioCaptureService service = new AudioCaptureService(
                bridge,
                new FakeGameEnvironment(),
                (speaking, sessionId, occurredAtMillis) -> states.add(speaking)
        );
        service.setFrameProcessor(new HighPassFilterProcessor(16000, 80.0D));

        service.startStreamCapture(42L, ignored -> { });
        repeat(5, () -> bridge.push(pcm(0.0D, 1600)));
        repeat(20, () -> bridge.push(sinePcm(0.015D, 220.0D, 16000, 1600)));
        repeat(50, () -> bridge.push(pcm(0.0D, 1600)));

        assertEquals(List.of(true, false), states);
    }

    @Test
    void streamCaptureKeepsDetectingAfterManualCommitBoundary() {
        FakeAudioBridge bridge = new FakeAudioBridge();
        List<AsrSpeechSegmenter.Decision> decisions = new ArrayList<>();
        AudioCaptureService service = new AudioCaptureService(
                bridge,
                new FakeGameEnvironment(),
                new AsrVadSpeechSegmenter((speaking, sessionId, occurredAtMillis) -> { })
        );

        service.startStreamCapture(42L, (chunk, decision) -> decisions.add(decision));
        emitSpeechAndSilence(bridge);
        service.resetStreamSegmentBoundary();
        emitSpeechAndSilence(bridge);

        assertEquals(2, decisions.stream().filter(AsrSpeechSegmenter.Decision::startsSegment).count());
        assertEquals(2, decisions.stream().filter(AsrSpeechSegmenter.Decision::endsSegment).count());
    }

    @Test
    void lateStreamCallbackAfterStopCannotReachTheConsumer() {
        FakeAudioBridge bridge = new FakeAudioBridge();
        List<AsrSpeechSegmenter.Decision> decisions = new ArrayList<>();
        AudioCaptureService service = new AudioCaptureService(
                bridge,
                new FakeGameEnvironment(),
                new AsrVadSpeechSegmenter((speaking, sessionId, occurredAtMillis) -> { })
        );

        service.startStreamCapture(42L, (chunk, decision) -> decisions.add(decision));
        Consumer<byte[]> staleCallback = bridge.lastStreamConsumer;
        service.stopStreamCapture();

        staleCallback.accept(pcm(0.03D, 160));

        assertEquals(List.of(), decisions);
    }

    @Test
    void replacingSegmenterDuringStreamRebindsTheActiveSession() {
        FakeAudioBridge bridge = new FakeAudioBridge();
        RecordingSegmenter original = new RecordingSegmenter();
        RecordingSegmenter replacement = new RecordingSegmenter();
        AudioCaptureService service = new AudioCaptureService(
                bridge,
                new FakeGameEnvironment(),
                original
        );

        service.startStreamCapture(42L, (chunk, decision) -> { });
        service.setSpeechSegmenter(replacement);

        assertEquals(List.of(42L), original.startedSessions);
        assertEquals(List.of(42L), replacement.startedSessions);
    }

    @Test
    void ordinaryStopFailureDoesNotPreventRemainingHardwareCleanup() {
        FakeAudioBridge bridge = new FakeAudioBridge();
        bridge.stopRecordingFailure = new IllegalStateException("PTT stop failed");
        AudioCaptureService service = new AudioCaptureService(bridge, new FakeGameEnvironment());

        service.releaseHardware();

        assertEquals(1, bridge.stopRecordingCalls);
        assertEquals(1, bridge.stopStreamRecordingCalls);
        assertEquals(1, bridge.releaseCaptureHardwareCalls);
    }

    @Test
    void nativeStopFailureDoesNotPreventRemainingHardwareCleanup() {
        FakeAudioBridge bridge = new FakeAudioBridge();
        bridge.stopRecordingFailure = new UnsatisfiedLinkError("native recorder missing");
        AudioCaptureService service = new AudioCaptureService(bridge, new FakeGameEnvironment());

        service.releaseHardware();

        assertEquals(1, bridge.stopRecordingCalls);
        assertEquals(1, bridge.stopStreamRecordingCalls);
        assertEquals(1, bridge.releaseCaptureHardwareCalls);
    }

    @Test
    void fatalJvmFailureIsNotDowngradedDuringCleanup() {
        FakeAudioBridge bridge = new FakeAudioBridge();
        OutOfMemoryError fatal = new OutOfMemoryError("fatal");
        bridge.stopRecordingFailure = fatal;
        AudioCaptureService service = new AudioCaptureService(bridge, new FakeGameEnvironment());

        assertSame(fatal, assertThrows(OutOfMemoryError.class, service::releaseHardware));
        assertEquals(1, bridge.stopRecordingCalls);
        assertEquals(0, bridge.stopStreamRecordingCalls);
        assertEquals(0, bridge.releaseCaptureHardwareCalls);
    }

    private static void repeat(int count, Runnable runnable) {
        for (int i = 0; i < count; i++) {
            runnable.run();
        }
    }

    /**
     * The detector measures the ambient floor during its startup window before deciding anything,
     * so tests feed a quiet stretch first and then a clearly louder one.
     */
    private static void emitNoise(FakeAudioBridge bridge, int frames) {
        byte[] quiet = pcm(0.002D, 160);
        repeat(frames, () -> bridge.push(quiet));
    }

    private static void emitSpeech(FakeAudioBridge bridge, int frames) {
        // Well above the calibrated floor so the segment actually opens.
        byte[] speech = pcm(0.06D, 160);
        repeat(frames, () -> bridge.push(speech));
    }

    private static void emitSpeechAndSilence(FakeAudioBridge bridge) {
        repeat(5, () -> bridge.push(pcm(0.0D, 1600)));
        repeat(20, () -> bridge.push(sinePcm(0.015D, 220.0D, 16000, 1600)));
        repeat(50, () -> bridge.push(pcm(0.0D, 1600)));
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

    private static byte[] sinePcm(double amplitude, double frequency, int sampleRate, int samples) {
        byte[] audio = new byte[samples * 2];
        for (int sample = 0; sample < samples; sample++) {
            short value = (short) Math.round(Math.sin(2.0D * Math.PI * frequency * sample / sampleRate)
                    * Math.max(-1.0D, Math.min(1.0D, amplitude)) * Short.MAX_VALUE);
            audio[sample * 2] = (byte) (value & 0xFF);
            audio[sample * 2 + 1] = (byte) ((value >>> 8) & 0xFF);
        }
        return audio;
    }

    private static final class FakeAudioBridge implements IAudioBridge {
        private Consumer<byte[]> streamConsumer;
        private Consumer<byte[]> lastStreamConsumer;
        private Throwable stopRecordingFailure;
        private int stopRecordingCalls;
        private int stopStreamRecordingCalls;
        private int releaseCaptureHardwareCalls;

        @Override
        public void ensureHardwareRunning() {
        }

        @Override
        public void releaseCaptureHardware() {
            releaseCaptureHardwareCalls++;
        }

        @Override
        public void startRecording() {
        }

        @Override
        public byte[] stopRecording() {
            stopRecordingCalls++;
            throwFailure(stopRecordingFailure);
            return new byte[0];
        }

        @Override
        public void startStreamRecording(Consumer<byte[]> onAudioChunk) {
            streamConsumer = onAudioChunk;
            lastStreamConsumer = onAudioChunk;
        }

        @Override
        public void stopStreamRecording() {
            stopStreamRecordingCalls++;
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

        private static void throwFailure(Throwable failure) {
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }

    private static final class RecordingSegmenter implements AsrSpeechSegmenter {
        private final List<Long> startedSessions = new ArrayList<>();

        @Override
        public void start(long sessionId) {
            startedSessions.add(sessionId);
        }
    }

    private static final class FakeGameEnvironment implements IGameEnvironment {
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
    @Test
    void aHeldUtteranceIsNotTruncatedByDefault() {
        // Product decision: how long the player speaks is their business, so nothing is cut unless an operator asks.
        FakeAudioBridge bridge = new FakeAudioBridge();
        AudioCaptureService service = new AudioCaptureService(bridge, new FakeGameEnvironment());

        service.startPttCapture();
        for (int burst = 0; burst < 8; burst++) {
            bridge.push(new byte[1_000_000]);
        }
        byte[] captured = service.stopPttCapture();

        assertEquals(8_000_000, captured.length, "the whole hold is kept when no limit is configured");
    }

    @Test
    void aConfiguredLimitTruncatesAndSaysSo() {
        FakeAudioBridge bridge = new FakeAudioBridge();
        RecordingEnvironment env = new RecordingEnvironment();
        AudioCaptureService service = new AudioCaptureService(bridge, env,
                com.rheinmetal.tianshu.intake.recognition.AsrSpeechSegmenter.disabled(), () -> 30);

        for (int turn = 0; turn < 2; turn++) {
            service.startPttCapture();
            // 30 seconds of 16 kHz mono 16-bit audio is 960 000 bytes; push twice that.
            for (int burst = 0; burst < 4; burst++) {
                bridge.push(new byte[480_000]);
            }
            byte[] captured = service.stopPttCapture();
            assertEquals(960_000, captured.length, "a configured limit is a hard bound");
        }

        assertEquals(2, env.warnings.size(), "each truncated turn must report its own cut");
        org.junit.jupiter.api.Assertions.assertTrue(env.warnings.stream().allMatch(line -> line.contains("configured")),
                "a cut must be reported, or it looks like a dead microphone: " + env.warnings);
    }

    /** Captures warnings so a test can see whether the player was told about a cut. */
    private static final class RecordingEnvironment implements IGameEnvironment {
        private final List<String> warnings = new ArrayList<>();
        private final IGameEnvironment delegate = new FakeGameEnvironment();

        @Override
        public void warn(String msg) {
            warnings.add(msg);
        }

        @Override
        public void executeOnMainThread(Runnable task) {
            delegate.executeOnMainThread(task);
        }

        @Override
        public Path getGameDirectory() {
            return delegate.getGameDirectory();
        }

        @Override
        public boolean isClientSide() {
            return delegate.isClientSide();
        }

        @Override
        public void openFolder(Path dir) {
            delegate.openFolder(dir);
        }

        @Override
        public void info(String msg) {
            delegate.info(msg);
        }

        @Override
        public void error(String msg, Throwable t) {
            delegate.error(msg, t);
        }

        @Override
        public com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink diagnostics() {
            return delegate.diagnostics();
        }
    }
}
