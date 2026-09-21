package com.rheinmetal.tianshu.function.asr.audio;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.asr.recognition.AsrSpeechSegmenter;
import com.rheinmetal.tianshu.function.asr.recognition.AsrVadSpeechSegmenter;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void streamCaptureForwardsSpeechSegmentBoundariesWithProcessedAudio() {
        FakeAudioBridge bridge = new FakeAudioBridge();
        List<AsrSpeechSegmenter.Decision> decisions = new ArrayList<>();
        AudioCaptureService service = new AudioCaptureService(bridge, new FakeGameEnvironment(),
                new com.rheinmetal.tianshu.function.asr.recognition.AsrVadSpeechSegmenter((speaking, sessionId, occurredAtMillis) -> {
                }));

        service.startStreamCapture(42L, (chunk, decision) -> decisions.add(decision));
        repeat(510, () -> bridge.push(pcm(0.03D, 160)));
        repeat(25, () -> bridge.push(pcm(0.0D, 160)));

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
    void streamCaptureRecordsRawAndProcessedWaveformsForEnabledDiagnostics() {
        FakeAudioBridge bridge = new FakeAudioBridge();
        AsrAudioDiagnostics diagnostics = new AsrAudioDiagnostics();
        diagnostics.setEnabled(true);
        AudioCaptureService service = new AudioCaptureService(
                bridge,
                new FakeGameEnvironment(),
                new AsrVadSpeechSegmenter((speaking, sessionId, occurredAtMillis) -> { }),
                diagnostics
        );
        service.setFrameProcessor(audio -> pcm(0.01D, audio.length / 2));

        service.startStreamCapture(42L, ignored -> { });
        bridge.push(pcm(0.04D, 160));

        AsrAudioDiagnostics.Snapshot snapshot = diagnostics.snapshot();
        assertEquals(1, snapshot.sampleCount());
        assertEquals(0.04F, snapshot.rawMax()[0], 0.01F);
        assertEquals(0.01F, snapshot.processedMax()[0], 0.01F);
        assertEquals(42L, snapshot.sessionId());

        service.stopStreamCapture();
        assertFalse(diagnostics.snapshot().captureActive());
    }

    @Test
    void diagnosticsPreservesRawWaveformWhenProcessorMutatesInput() {
        FakeAudioBridge bridge = new FakeAudioBridge();
        AsrAudioDiagnostics diagnostics = new AsrAudioDiagnostics();
        diagnostics.setEnabled(true);
        AudioCaptureService service = new AudioCaptureService(
                bridge,
                new FakeGameEnvironment(),
                AsrSpeechSegmenter.disabled(),
                diagnostics
        );
        service.setFrameProcessor(audio -> {
            for (int index = 0; index + 1 < audio.length; index += 2) {
                audio[index] = 0;
                audio[index + 1] = 0;
            }
            return audio;
        });

        service.startStreamCapture(42L, ignored -> { });
        bridge.push(pcm(0.04D, 160));

        AsrAudioDiagnostics.Snapshot snapshot = diagnostics.snapshot();
        assertEquals(0.04F, snapshot.rawMax()[0], 0.01F);
        assertEquals(0.0F, snapshot.processedMax()[0], 0.001F);
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

    private static void emitSpeechAndSilence(FakeAudioBridge bridge) {
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
