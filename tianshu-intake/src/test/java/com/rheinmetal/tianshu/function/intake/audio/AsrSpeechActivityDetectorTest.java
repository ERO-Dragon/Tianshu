package com.rheinmetal.tianshu.function.intake.audio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The detector is deliberately simple, so these tests cover only its three rules: the floor follows
 * quiet input, a level above floor * START_RATIO opens a segment, and silence closes it.
 */
class AsrSpeechActivityDetectorTest {
    private static final int SAMPLE_RATE = 16000;
    /** Ten milliseconds per frame. */
    private static final int FRAME = 160;

    @Test
    void idleInputIsNotSpeech() {
        List<Boolean> states = new ArrayList<>();
        AsrSpeechActivityDetector detector = new AsrSpeechActivityDetector(SAMPLE_RATE, (speaking, s, t) -> states.add(speaking));

        detector.start(42L);
        feed(detector, 0.03D, 100);

        assertEquals(List.of(), states, "steady idle input must never be reported as speech");
    }

    @Test
    void speechOpensASegmentAndSilenceClosesIt() {
        List<Boolean> states = new ArrayList<>();
        AsrSpeechActivityDetector detector = new AsrSpeechActivityDetector(SAMPLE_RATE, (speaking, s, t) -> states.add(speaking));

        detector.start(42L);
        feed(detector, 0.03D, 60);   // settle the floor on the idle level
        feed(detector, 0.06D, 30);   // speech, clearly above it
        feed(detector, 0.03D, 80);   // back to idle, long enough to release

        assertEquals(List.of(true, false), states);
    }

    @Test
    void aShortBlipIsNotReported() {
        List<Boolean> states = new ArrayList<>();
        AsrSpeechActivityDetector detector = new AsrSpeechActivityDetector(SAMPLE_RATE, (speaking, s, t) -> states.add(speaking));

        detector.start(42L);
        feed(detector, 0.03D, 60);
        feed(detector, 0.06D, 5);    // 50 ms, shorter than the minimum segment
        feed(detector, 0.03D, 80);

        assertEquals(List.of(), states, "a click must not reach the recogniser");
    }

    @Test
    void aVoiceDoesNotRaiseTheFloor() {
        List<Boolean> states = new ArrayList<>();
        AsrSpeechActivityDetector detector = new AsrSpeechActivityDetector(SAMPLE_RATE, (speaking, s, t) -> states.add(speaking));

        detector.start(42L);
        feed(detector, 0.03D, 60);
        double before = detector.snapshot().noiseFloor();

        feed(detector, 0.09D, 100);   // a long stretch of speech

        // The floor follows quiet input only, so the thresholds cannot climb onto the voice.
        assertEquals(before, detector.snapshot().noiseFloor(), before * 0.05D,
                "speech must not drag the floor upward");
    }

    @Test
    void theFloorLearnsWhateverTheDeviceIdleLevelIs() {
        for (double idle : new double[]{0.002D, 0.03D, 0.1D, 0.2D}) {
            AsrSpeechActivityDetector detector = new AsrSpeechActivityDetector(SAMPLE_RATE, (s, i, t) -> { });

            detector.start(42L);
            feed(detector, idle, 100);

            AsrSpeechActivityDetector snapshot = detector;
            assertEquals(idle, detector.snapshot().noiseFloor(), idle * 0.15D,
                    "the floor must converge onto the device idle level " + idle);
            assertTrue(detector.snapshot().startThreshold() > idle,
                    "the start threshold must sit above idle level " + idle);
            assertFalse(detector.snapshot().speaking(),
                    "idle input at " + idle + " must not open a segment");
        }
    }

    @Test
    void chunksOfDifferentSizesBehaveAlike() {
        for (int samples : new int[]{80, 160, 480}) {
            List<Boolean> states = new ArrayList<>();
            AsrSpeechActivityDetector detector = new AsrSpeechActivityDetector(SAMPLE_RATE, (speaking, s, t) -> states.add(speaking));

            detector.start(42L);
            // Expressed in milliseconds of audio so every frame size receives the same amount of
            // sound: 500 ms idle, 500 ms speech, then 2 s idle.
            int frameMillis = Math.max(1, samples * 1000 / SAMPLE_RATE);
            feed(detector, 0.03D, frames(500, frameMillis), samples);
            feed(detector, 0.06D, frames(500, frameMillis), samples);
            feed(detector, 0.03D, frames(2000, frameMillis), samples);

            assertEquals(List.of(true, false), states, "frame size " + samples + " must behave alike");
        }
    }

    private static int frames(int millis, int frameMillis) {
        return Math.max(1, (int) Math.ceil((double) millis / frameMillis));
    }

    private static void feed(AsrSpeechActivityDetector detector, double level, int frames) {
        feed(detector, level, frames, FRAME);
    }

    private static void feed(AsrSpeechActivityDetector detector, double level, int frames, int samples) {
        byte[] frame = constantPcm(level, samples);
        for (int index = 0; index < frames; index++) {
            detector.accept(frame);
        }
    }

    private static byte[] constantPcm(double level, int samples) {
        byte[] audio = new byte[samples * 2];
        short value = (short) Math.round(Math.max(-1.0D, Math.min(1.0D, level)) * Short.MAX_VALUE);
        for (int index = 0; index < samples; index++) {
            audio[index * 2] = (byte) (value & 0xFF);
            audio[index * 2 + 1] = (byte) ((value >>> 8) & 0xFF);
        }
        return audio;
    }
}
