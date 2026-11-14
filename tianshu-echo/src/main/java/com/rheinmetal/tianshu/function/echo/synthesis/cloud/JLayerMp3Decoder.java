package com.rheinmetal.tianshu.function.echo.synthesis.cloud;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

/**
 * MP3 → PCM16 (little endian) through JLayer.
 *
 * <p>Layer III is not frame-independent: a frame's side information points back into the previous frames'
 * main data (the bit reservoir), and decoding a frame on its own yields silence for most streams — the
 * probe on the shipped fixture decoded one frame of 78 that way. Feeding the whole utterance to one decoder
 * would fix the audio but break the contract this module needs: the sink must receive PCM as the socket
 * delivers it, and chunk boundaries must not change the result.</p>
 *
 * <p>So each frame is decoded together with a bounded window of the frames before it, and only the newest
 * frame's samples are emitted. The window is re-decoded on every call, which keeps the decoder stateless
 * across chunks — the same bytes produce the same PCM whether they arrive as one block or twenty.</p>
 *
 * <p>{@code MAIN_DATA_LOOKBACK_FRAMES} covers the reservoir's worst case: ISO 11172-3 limits
 * {@code main_data_begin} to 511 bytes, and the smallest Layer III frame this decoder can meet is around
 * 100 bytes, so eight frames of history always contain the data a frame can point at. The cost is bounded
 * (nine cheap decodes per frame at 24 kHz) and paid on the synthesis lane, not the game thread.</p>
 */
public final class JLayerMp3Decoder implements Mp3Decoder {

    private static final int MAIN_DATA_LOOKBACK_FRAMES = 8;

    private final Mp3FrameSplitter splitter = new Mp3FrameSplitter();
    private final Deque<byte[]> history = new ArrayDeque<>(MAIN_DATA_LOOKBACK_FRAMES + 1);

    @Override
    public void decode(byte[] encoded, Consumer<byte[]> pcmSink) {
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        for (byte[] frame : splitter.feed(encoded)) {
            appendFrame(frame, pcm);
            remember(frame);
        }
        if (pcm.size() > 0) {
            pcmSink.accept(pcm.toByteArray());
        }
    }

    private void appendFrame(byte[] frame, ByteArrayOutputStream pcm) {
        ByteArrayOutputStream window = new ByteArrayOutputStream();
        history.forEach(window::writeBytes);
        window.writeBytes(frame);

        Decoder decoder = new Decoder();
        Bitstream bitstream = new Bitstream(new ByteArrayInputStream(window.toByteArray()));
        try {
            Header header = bitstream.readFrame();
            for (int index = 0; index < history.size() && header != null; index++) {
                decoder.decodeFrame(header, bitstream);
                bitstream.closeFrame();
                header = bitstream.readFrame();
            }
            if (header != null) {
                Object decoded = decoder.decodeFrame(header, bitstream);
                if (decoded instanceof SampleBuffer sampleBuffer) {
                    appendSamples(sampleBuffer, pcm);
                }
                bitstream.closeFrame();
            }
        } catch (Exception failure) {
            throw new IllegalStateException("MP3 frame could not be decoded: " + failure.getMessage(), failure);
        } finally {
            try {
                bitstream.close();
            } catch (Exception ignored) {
                // Closing a frame-local stream cannot fail in a way the caller could act on.
            }
        }
    }

    private void remember(byte[] frame) {
        history.addLast(frame);
        while (history.size() > MAIN_DATA_LOOKBACK_FRAMES) {
            history.removeFirst();
        }
    }

    /** Drops the decode history; called when the utterance is finished or cancelled. */
    public void reset() {
        history.clear();
        splitter.clear();
    }

    private static void appendSamples(SampleBuffer buffer, ByteArrayOutputStream pcm) {
        short[] samples = buffer.getBuffer();
        int length = buffer.getBufferLength();
        for (int index = 0; index < length; index++) {
            short sample = samples[index];
            pcm.write(sample & 0xFF);
            pcm.write((sample >> 8) & 0xFF);
        }
    }
}