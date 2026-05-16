package com.rheinmetal.tianshu.function.tts.synthesis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TtsPcm16AudioConverterTest {
    @Test
    void clampsMonoSamplesToPcm16() {
        byte[] pcm = TtsPcm16AudioConverter.fromMonoFloat(new float[]{-2.0f, 0.0f, 1.0f});

        assertEquals(6, pcm.length);
        assertArrayEquals(new byte[]{0, -128, 0, 0, -1, 127}, pcm);
    }

    @Test
    void downmixesChannelsBeforeEncoding() {
        byte[] pcm = TtsPcm16AudioConverter.fromChannels(new float[][]{
                new float[]{1.0f, -1.0f},
                new float[]{1.0f, 1.0f}
        });

        assertEquals(4, pcm.length);
    }
}
