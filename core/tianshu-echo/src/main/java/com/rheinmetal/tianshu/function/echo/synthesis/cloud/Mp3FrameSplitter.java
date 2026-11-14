package com.rheinmetal.tianshu.function.echo.synthesis.cloud;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Re-frames a byte stream into whole MP3 frames.
 *
 * <p>The provider hands out arbitrary slices: a chunk can end in the middle of a frame and the next one can
 * start with the rest of it. A decoder that assumes chunk == frame produces exactly one audible glitch per
 * boundary, so the split is done here, once, and the decoder only ever sees complete frames.</p>
 *
 * <p>Every table below is part of the MP3 specification (ISO/IEC 11172-3 / 13818-3), not provider data.</p>
 */
public final class Mp3FrameSplitter {

    private static final int[][] BITRATES_KBPS = {
            // MPEG1 layer III
            {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0},
            // MPEG2 / MPEG2.5 layer III
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0},
    };
    private static final int[] SAMPLE_RATES_MPEG1 = {44100, 48000, 32000};
    private static final int[] SAMPLE_RATES_MPEG2 = {22050, 24000, 16000};
    private static final int[] SAMPLE_RATES_MPEG25 = {11025, 12000, 8000};

    private byte[] pending = new byte[0];

    /**
     * Appends bytes and returns every frame that is now complete.
     *
     * <p>Bytes that cannot be a frame header are skipped one at a time while looking for the next sync word,
     * which is what makes this tolerate a truncated first frame or an ID3 tag at the start.</p>
     */
    public List<byte[]> feed(byte[] chunk) {
        if (chunk == null || chunk.length == 0) {
            return List.of();
        }
        byte[] buffer = new byte[pending.length + chunk.length];
        System.arraycopy(pending, 0, buffer, 0, pending.length);
        System.arraycopy(chunk, 0, buffer, pending.length, chunk.length);

        List<byte[]> frames = new ArrayList<>();
        int offset = 0;
        while (true) {
            int sync = nextSync(buffer, offset);
            if (sync < 0) {
                // No sync at all: keep the last three bytes, since a header may straddle the boundary.
                offset = Math.max(offset, buffer.length - 3);
                break;
            }
            if (buffer.length - sync < 4) {
                offset = sync;
                break;
            }
            int frameLength = frameLength(buffer, sync);
            if (frameLength <= 0) {
                if (!isFrameHeader(buffer, sync)) {
                    offset = sync + 1;
                    continue;
                }
                offset = sync;
                break;
            }
            if (buffer.length - sync < frameLength) {
                offset = sync;
                break;
            }
            frames.add(Arrays.copyOfRange(buffer, sync, sync + frameLength));
            offset = sync + frameLength;
        }
        pending = Arrays.copyOfRange(buffer, offset, buffer.length);
        return frames;
    }

    /** Drops whatever partial frame is still buffered; called when the utterance ends. */
    public void clear() {
        pending = new byte[0];
    }

    private static int nextSync(byte[] buffer, int from) {
        for (int index = Math.max(from, 0); index + 1 < buffer.length; index++) {
            if ((buffer[index] & 0xFF) == 0xFF && (buffer[index + 1] & 0xE0) == 0xE0) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isFrameHeader(byte[] buffer, int offset) {
        if (offset + 4 > buffer.length) {
            return false;
        }
        int b1 = buffer[offset + 1] & 0xFF;
        int b2 = buffer[offset + 2] & 0xFF;
        int b3 = buffer[offset + 3] & 0xFF;
        int version = (b1 >> 3) & 0x03;
        int layer = (b1 >> 1) & 0x03;
        int bitrateIndex = (b2 >> 4) & 0x0F;
        int sampleRateIndex = (b2 >> 2) & 0x03;
        return version != 0x01
                && layer == 0x01
                && bitrateIndex != 0x00 && bitrateIndex != 0x0F
                && sampleRateIndex != 0x03
                && (b3 & 0x03) != 0x02;
    }

    private static int frameLength(byte[] buffer, int offset) {
        if (!isFrameHeader(buffer, offset)) {
            return -1;
        }
        int b1 = buffer[offset + 1] & 0xFF;
        int b2 = buffer[offset + 2] & 0xFF;
        int version = (b1 >> 3) & 0x03;
        int bitrateIndex = (b2 >> 4) & 0x0F;
        int sampleRateIndex = (b2 >> 2) & 0x03;
        int padding = (b2 >> 1) & 0x01;
        boolean mpeg1 = version == 0x03;
        int bitrate = BITRATES_KBPS[mpeg1 ? 0 : 1][bitrateIndex] * 1000;
        int sampleRate = sampleRate(version, sampleRateIndex);
        if (bitrate <= 0 || sampleRate <= 0) {
            return -1;
        }
        int coefficient = mpeg1 ? 144 : 72;
        return coefficient * bitrate / sampleRate + padding;
    }

    private static int sampleRate(int version, int index) {
        return switch (version) {
            case 0x03 -> SAMPLE_RATES_MPEG1[index];
            case 0x02 -> SAMPLE_RATES_MPEG2[index];
            case 0x00 -> SAMPLE_RATES_MPEG25[index];
            default -> 0;
        };
    }
}