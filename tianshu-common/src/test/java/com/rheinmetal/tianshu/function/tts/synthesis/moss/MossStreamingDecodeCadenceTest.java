package com.rheinmetal.tianshu.function.tts.synthesis.moss;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MossStreamingDecodeCadenceTest {
    private static final int SAMPLE_RATE = 48_000;
    private static final long SECOND_NANOS = 1_000_000_000L;

    @Test
    void upstreamCadenceUsesOneTwoFourAndEightFramesFromAudioLead() {
        MossStreamingDecodeCadence cadence = MossStreamingDecodeCadence.upstreamAdaptive();

        assertEquals(1, cadence.nextFrameCount(0, SAMPLE_RATE, -1L, 0L));
        assertEquals(1, cadence.nextFrameCount(48_000L, SAMPLE_RATE, 0L, 810_000_000L));
        assertEquals(2, cadence.nextFrameCount(48_000L, SAMPLE_RATE, 0L, 790_000_000L));
        assertEquals(4, cadence.nextFrameCount(48_000L, SAMPLE_RATE, 0L, 450_000_000L));
        assertEquals(8, cadence.nextFrameCount(52_800L, SAMPLE_RATE, 0L, 0L));
    }

    @Test
    void fixedCadenceIgnoresAudioLead() {
        MossStreamingDecodeCadence cadence = MossStreamingDecodeCadence.fixed(8);

        assertEquals(8, cadence.nextFrameCount(0L, SAMPLE_RATE, -1L, 0L));
        assertEquals(8, cadence.nextFrameCount(48_000L, SAMPLE_RATE, 0L, SECOND_NANOS));
    }
}
