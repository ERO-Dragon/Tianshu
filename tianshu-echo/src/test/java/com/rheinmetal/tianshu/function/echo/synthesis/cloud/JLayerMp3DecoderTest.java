package com.rheinmetal.tianshu.function.echo.synthesis.cloud;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decoder has to be chunk-boundary agnostic: the streaming path feeds it whatever the socket delivered,
 * and the complete-result path feeds it the same bytes in one go. Both must yield identical PCM.
 */
class JLayerMp3DecoderTest {

    @Test
    void oneShotDecodingProducesPcm16() throws IOException {
        byte[] mp3 = fixture();
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();

        new JLayerMp3Decoder().decode(mp3, pcm::writeBytes);

        assertTrue(pcm.size() > 0, "decoding produced no PCM");
        assertEquals(0, pcm.size() % 2, "PCM16 output has to be an even number of bytes");
        // 24 kHz mono, ~1s of speech: sanity bound rather than an exact length.
        assertTrue(pcm.size() > 20_000, "expected roughly a second of audio, got " + pcm.size() + " bytes");
    }

    @Test
    void chunkedDecodingMatchesOneShotDecoding() throws IOException {
        byte[] mp3 = fixture();
        ByteArrayOutputStream oneShot = new ByteArrayOutputStream();
        new JLayerMp3Decoder().decode(mp3, oneShot::writeBytes);

        ByteArrayOutputStream chunked = new ByteArrayOutputStream();
        JLayerMp3Decoder decoder = new JLayerMp3Decoder();
        for (int offset = 0; offset < mp3.length; offset += 641) {
            int end = Math.min(mp3.length, offset + 641);
            decoder.decode(Arrays.copyOfRange(mp3, offset, end), chunked::writeBytes);
        }

        assertArrayEquals(oneShot.toByteArray(), chunked.toByteArray(),
                "chunk boundaries must not change the decoded audio");
    }

    @Test
    void chunksAreNotEmittedAsSingleByteArrays() throws IOException {
        byte[] mp3 = fixture();
        List<byte[]> blocks = new ArrayList<>();

        new JLayerMp3Decoder().decode(mp3, blocks::add);

        assertEquals(1, blocks.size(), "one call decoding whole frames emits one block");
        assertTrue(blocks.get(0).length > 0);
    }

    private static byte[] fixture() throws IOException {
        try (InputStream stream = JLayerMp3DecoderTest.class.getResourceAsStream("/cloud/edge-sample.mp3")) {
            assertTrue(stream != null, "test fixture is missing: /cloud/edge-sample.mp3");
            return stream.readAllBytes();
        }
    }
}