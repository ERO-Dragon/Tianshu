package com.rheinmetal.tianshu.function.echo.synthesis.cloud;

import java.util.function.Consumer;

/**
 * Streams the encoded audio of one utterance.
 *
 * <p>The transport knows the provider's wire protocol; it does not know PCM, sample rates or the synthesis
 * lane. Keeping the encoding at this seam is what lets the backend own decoding, so a provider that starts
 * serving a different format is a decoder change rather than a transport rewrite.</p>
 */
public interface CloudTtsTransport extends AutoCloseable {

    /**
     * Speaks {@code text} and hands each encoded chunk to {@code onEncodedAudio} as it arrives.
     *
     * @throws com.rheinmetal.tianshu.function.echo.runtime.TtsFailureException when the provider refuses or
     *         the connection fails; the code carries which of those happened
     */
    void speak(TtsCloudProfile profile, String text, Consumer<byte[]> onEncodedAudio);

    /** Aborts the utterance that is in flight; safe to call when nothing is running. */
    void cancel();

    @Override
    void close();
}