package com.rheinmetal.tianshu.function.asr.audio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AsrSpeechActivityDetectorTest {
    @Test
    void publishesOnlyWhenProcessedAudioActivityChanges() {
        List<Boolean> states = new ArrayList<>();
        AsrSpeechActivityDetector detector = new AsrSpeechActivityDetector(0.01D, 0.005D, 1, 2, (speaking, sessionId, occurredAtMillis) -> states.add(speaking));

        detector.start(42L);
        detector.accept(pcm(0.02D, 160));
        detector.accept(pcm(0.03D, 160));
        detector.accept(pcm(0.0D, 160));
        detector.accept(pcm(0.0D, 160));

        assertEquals(List.of(true, false), states);
    }

    @Test
    void stopPublishesSilentIfSpeechIsActive() {
        List<Boolean> states = new ArrayList<>();
        AsrSpeechActivityDetector detector = new AsrSpeechActivityDetector(0.01D, 0.005D, 1, 2, (speaking, sessionId, occurredAtMillis) -> states.add(speaking));

        detector.start(42L);
        detector.accept(pcm(0.02D, 160));
        detector.stop();

        assertEquals(List.of(true, false), states);
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
