package com.rheinmetal.tianshu.function.echo.synthesis.cloud;

import com.rheinmetal.tianshu.function.echo.runtime.TtsFailureCode;
import com.rheinmetal.tianshu.function.echo.runtime.TtsFailureException;
import com.rheinmetal.tianshu.function.echo.runtime.TtsRequest;
import com.rheinmetal.tianshu.function.echo.synthesis.TtsAudioSink;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The cloud backend: transport in, PCM out.
 *
 * <p>It owns the two things the provider cannot know about: decoding (the endpoint serves MP3) and the
 * cancel contract (an interrupt has to stop the socket, not just ignore its output — otherwise the request
 * keeps running at the provider). The provider's protocol lives behind {@link CloudTtsTransport}, so the
 * failure codes it raises survive to the runtime through {@link TtsFailureException}.</p>
 */
public final class TransportCloudTtsBackend implements CloudTtsBackend {

    private final CloudTtsTransport transport;
    private final Mp3Decoder decoder;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private TtsCloudProfile profile;
    private volatile boolean initialized;

    public TransportCloudTtsBackend(CloudTtsTransport transport, Mp3Decoder decoder) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    @Override
    public synchronized boolean initialize(TtsCloudProfile profile) {
        if (profile == null || profile.providerId().isEmpty()) {
            initialized = false;
            return false;
        }
        this.profile = profile;
        this.initialized = true;
        return true;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public int sampleRate() {
        // The endpoint's MP3 is 24 kHz mono; the decoder emits PCM16 at the same rate, so no resampling.
        return 24_000;
    }


    @Override
    public void synthesize(TtsRequest request, TtsAudioSink sink) {
        if (!initialized) {
            throw new TtsFailureException(TtsFailureCode.SYNTHESIS_ENGINE_UNAVAILABLE,
                    "TTS cloud backend is not initialized");
        }
        if (request == null || request.text() == null || request.text().isBlank()) {
            throw new TtsFailureException(TtsFailureCode.EMPTY_TEXT, "TTS cloud request text is empty");
        }
        cancelled.set(false);
        TtsCloudProfile target = profile;
        try {
            transport.speak(target, request.text(), encoded -> onEncoded(encoded, sink));
        } catch (TtsFailureException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new TtsFailureException(TtsFailureCode.SYNTHESIS_FAILED,
                    "TTS cloud synthesis failed: " + failure.getMessage());
        }
    }

    private void onEncoded(byte[] encoded, TtsAudioSink sink) {
        if (cancelled.get() || encoded == null || encoded.length == 0) {
            return;
        }
        try {
            decoder.decode(encoded, pcm -> {
                if (!cancelled.get() && pcm != null && pcm.length > 0) {
                    sink.accept(pcm);
                }
            });
        } catch (RuntimeException decodeFailure) {
            // A decoder that cannot read a chunk means the provider's bytes are not what we agreed to speak;
            // reporting it as a synthesis failure would send the player looking for a local cause.
            throw new TtsFailureException(TtsFailureCode.CLOUD_INVALID_RESPONSE,
                    "TTS cloud audio could not be decoded: " + decodeFailure.getMessage());
        }
    }

    @Override
    public void interrupt() {
        cancelled.set(true);
        transport.cancel();
    }

    @Override
    public void shutdown() {
        cancelled.set(true);
        initialized = false;
        profile = null;
        transport.close();
    }
}