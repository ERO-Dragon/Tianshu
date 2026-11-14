package com.rheinmetal.tianshu.function.echo.synthesis.cloud;

import java.util.function.Consumer;

/**
 * Turns one encoded chunk into PCM16 and hands it on.
 *
 * <p>A chunk-level interface rather than a stream object: providers deliver arbitrary slices (which may split
 * a frame), so the decoder has to be able to buffer whatever arrives and emit only complete PCM blocks.</p>
 */
public interface Mp3Decoder {

    /**
     * Decodes {@code encoded} and passes the PCM16 produced by this chunk to {@code pcmSink}.
     *
     * @param pcmSink receives zero or more PCM blocks; never null
     * @throws RuntimeException when the bytes are not decodable
     */
    void decode(byte[] encoded, Consumer<byte[]> pcmSink);
}