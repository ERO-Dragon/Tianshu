package com.rheinmetal.tianshu.function.echo.synthesis.cloud;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Re-framing is the part a chunked provider makes visible: if it is wrong, the audio glitches once per chunk
 * boundary and nothing else in the pipeline can compensate for it.
 */
class Mp3FrameSplitterTest {

    @Test
    void aWholeFileSplitsIntoRealFrames() throws IOException {
        byte[] mp3 = fixture();
        List<byte[]> frames = new Mp3FrameSplitter().feed(mp3);

        assertTrue(frames.size() > 10, "expected many frames, got " + frames.size());
        int total = 0;
        for (byte[] frame : frames) {
            assertEquals(0xFF, frame[0] & 0xFF, "a frame must start at a sync word");
            assertEquals(0xE0, frame[1] & 0xE0, "a frame must start at a sync word");
            total += frame.length;
        }
        assertTrue(total <= mp3.length, "frames cannot be longer than their source");
        assertTrue(total > mp3.length / 2, "most of the file has to be covered by frames");
    }

    @Test
    void chunkedFeedingProducesTheSameFramesAsOneShot() throws IOException {
        byte[] mp3 = fixture();
        List<byte[]> oneShot = new Mp3FrameSplitter().feed(mp3);

        Mp3FrameSplitter chunked = new Mp3FrameSplitter();
        List<byte[]> collected = new java.util.ArrayList<>();
        for (int offset = 0; offset < mp3.length; offset += 997) {
            int end = Math.min(mp3.length, offset + 997);
            collected.addAll(chunked.feed(Arrays.copyOfRange(mp3, offset, end)));
        }

        assertEquals(oneShot.size(), collected.size(), "chunk boundaries must not change the framing");
        for (int index = 0; index < oneShot.size(); index++) {
            assertArrayEquals(oneShot.get(index), collected.get(index), "frame " + index + " differs");
        }
    }

    @Test
    void aTruncatedTailIsHeldUntilItsRestArrives() throws IOException {
        byte[] mp3 = fixture();
        byte[] firstHalf = Arrays.copyOfRange(mp3, 0, mp3.length / 2);
        byte[] secondHalf = Arrays.copyOfRange(mp3, mp3.length / 2, mp3.length);

        Mp3FrameSplitter splitter = new Mp3FrameSplitter();
        List<byte[]> frames = new java.util.ArrayList<>(splitter.feed(firstHalf));
        frames.addAll(splitter.feed(secondHalf));

        assertEquals(new Mp3FrameSplitter().feed(mp3).size(), frames.size());
    }

    private static byte[] fixture() throws IOException {
        try (InputStream stream = Mp3FrameSplitterTest.class.getResourceAsStream("/cloud/edge-sample.mp3")) {
            assertTrue(stream != null, "test fixture is missing: /cloud/edge-sample.mp3");
            return stream.readAllBytes();
        }
    }
}