package com.rheinmetal.tianshu.function.asr.audio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsrSpeechActivityDetectorTest {
    @Test
    void publishesSpeakingAsSoonAsAudioCrossesStartThreshold() {
        List<Boolean> states = new ArrayList<>();
        AsrSpeechActivityDetector detector = detector(states);

        detector.start(42L);
        detector.accept(pcm(0.02D, 160));

        assertEquals(List.of(true), states);
    }

    @Test
    void shortPauseDoesNotPublishSilent() {
        List<Boolean> states = new ArrayList<>();
        AsrSpeechActivityDetector detector = detector(states);

        detector.start(42L);
        detector.accept(pcm(0.02D, 160));
        repeat(20, () -> detector.accept(pcm(0.0D, 160)));
        detector.accept(pcm(0.03D, 160));

        assertEquals(List.of(true), states);
    }

    @Test
    void publishesSilentAfterConfiguredSilentMillisIndependentOfChunkSize() {
        List<Boolean> tenMillisStates = new ArrayList<>();
        AsrSpeechActivityDetector tenMillisDetector = detector(tenMillisStates);
        tenMillisDetector.start(42L);
        tenMillisDetector.accept(pcm(0.02D, 160));
        repeat(45, () -> tenMillisDetector.accept(pcm(0.0D, 160)));

        List<Boolean> thirtyMillisStates = new ArrayList<>();
        AsrSpeechActivityDetector thirtyMillisDetector = detector(thirtyMillisStates);
        thirtyMillisDetector.start(42L);
        thirtyMillisDetector.accept(pcm(0.02D, 480));
        repeat(15, () -> thirtyMillisDetector.accept(pcm(0.0D, 480)));

        assertEquals(List.of(true, false), tenMillisStates);
        assertEquals(List.of(true, false), thirtyMillisStates);
    }

    @Test
    void longSpeechUsesShorterStopHold() {
        List<Boolean> states = new ArrayList<>();
        AsrSpeechActivityDetector detector = detector(states);

        detector.start(42L);
        repeat(510, () -> detector.accept(pcm(0.02D, 160)));
        repeat(25, () -> detector.accept(pcm(0.0D, 160)));

        assertEquals(List.of(true, false), states);
    }

    @Test
    void noiseFloorIsCappedSoDetectorRecoversAfterLoudAmbientNoise() {
        List<Boolean> states = new ArrayList<>();
        AsrSpeechActivityDetector detector = new AsrSpeechActivityDetector(
                16000,
                0.05D,
                0.02D,
                3.0D,
                1.5D,
                0.03D,
                0.09D,
                0L,
                300L,
                2000L,
                5000L,
                450L,
                350L,
                250L,
                (speaking, sessionId, occurredAtMillis) -> states.add(speaking)
        );

        detector.start(42L);
        repeat(80, () -> detector.accept(pcm(0.08D, 160)));
        repeat(10, () -> detector.accept(pcm(0.0D, 160)));
        detector.accept(pcm(0.06D, 160));

        assertEquals(List.of(true), states);
    }

    @Test
    void stopPublishesSilentIfSpeechIsActive() {
        List<Boolean> states = new ArrayList<>();
        AsrSpeechActivityDetector detector = detector(states);

        detector.start(42L);
        detector.accept(pcm(0.02D, 160));
        detector.stop();

        assertEquals(List.of(true, false), states);
    }

    @Test
    void snapshotExposesCurrentSessionRmsThresholdsAndState() {
        AsrSpeechActivityDetector detector = detector(new ArrayList<>());

        detector.start(42L);
        detector.accept(pcm(0.02D, 160));

        AsrSpeechActivitySnapshot snapshot = detector.snapshot();

        assertEquals(42L, snapshot.sessionId());
        assertEquals(0.02D, snapshot.rms(), 0.002D);
        assertTrue(snapshot.speaking());
        assertTrue(snapshot.startThreshold() >= snapshot.stopThreshold());
        assertTrue(snapshot.occurredAtMillis() > 0L);
    }

    @Test
    void adaptsToAmbientAudioBelowStartThresholdAndEndsTheSegment() {
        List<Boolean> states = new ArrayList<>();
        AsrSpeechActivityDetector detector = detector(states);

        detector.start(42L);
        detector.accept(pcm(0.02D, 160));
        repeat(180, () -> detector.accept(pcm(0.004D, 160)));

        assertEquals(List.of(true, false), states);
    }

    @Test
    void endsSpeechWhenAmbientFloorIsAboveTheMinimumStopThreshold() {
        List<Boolean> states = new ArrayList<>();
        AsrSpeechActivityDetector detector = detector(states);

        detector.start(42L);
        detector.accept(pcm(0.08D, 160));
        repeat(50, () -> detector.accept(pcm(0.01D, 160)));

        assertEquals(List.of(true, false), states);
    }

    @Test
    void adaptsWhenStartupAmbientNoiseIsAboveTheDefaultStartThreshold() {
        List<Boolean> states = new ArrayList<>();
        AsrSpeechActivityDetector detector = new AsrSpeechActivityDetector(
                (speaking, sessionId, occurredAtMillis) -> states.add(speaking)
        );

        detector.start(42L);
        repeat(20, () -> detector.accept(pcm(0.01D, 160)));
        detector.accept(pcm(0.04D, 160));
        repeat(50, () -> detector.accept(pcm(0.01D, 160)));

        assertEquals(List.of(true, false), states);
    }

    private AsrSpeechActivityDetector detector(List<Boolean> states) {
        return new AsrSpeechActivityDetector(
                16000,
                0.012D,
                0.005D,
                3.0D,
                1.5D,
                0.002D,
                0.03D,
                300L,
                300L,
                2000L,
                5000L,
                450L,
                350L,
                250L,
                (speaking, sessionId, occurredAtMillis) -> states.add(speaking)
        );
    }

    private void repeat(int count, Runnable runnable) {
        for (int i = 0; i < count; i++) {
            runnable.run();
        }
    }

    private byte[] pcm(double amplitude, int samples) {
        byte[] audio = new byte[samples * 2];
        short value = (short) Math.round(Math.max(-1.0D, Math.min(1.0D, amplitude)) * Short.MAX_VALUE);
        for (int index = 0; index < audio.length; index += 2) {
            audio[index] = (byte) (value & 0xFF);
            audio[index + 1] = (byte) ((value >>> 8) & 0xFF);
        }
        return audio;
    }
}
