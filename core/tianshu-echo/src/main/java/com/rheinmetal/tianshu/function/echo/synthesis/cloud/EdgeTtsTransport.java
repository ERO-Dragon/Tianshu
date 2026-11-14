package com.rheinmetal.tianshu.function.echo.synthesis.cloud;

import com.rheinmetal.tianshu.function.echo.runtime.TtsFailureCode;
import com.rheinmetal.tianshu.function.echo.runtime.TtsFailureException;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Drives the Edge conversation: one URL, two frames out, audio frames in.
 *
 * <p>The provider-specific parts stay in two places — {@link EdgeTtsProtocol} for the text frames and
 * {@link EdgeTtsConnection} for the socket — so this class is only the ordering: config, then SSML, then
 * stream until the provider ends the turn. Cancellation closes the connection instead of ignoring its
 * output, because a provider-side request that keeps running is still billed and still occupies a slot.</p>
 */
public final class EdgeTtsTransport implements CloudTtsTransport {

    private final Supplier<EdgeTtsCredentials> credentials;
    private final EdgeTtsConnectionFactory connectionFactory;
    private volatile EdgeTtsConnection current;
    private volatile boolean cancelled;

    public EdgeTtsTransport(Supplier<EdgeTtsCredentials> credentials,
                            EdgeTtsConnectionFactory connectionFactory) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    }

    @Override
    public void speak(TtsCloudProfile profile, String text, Consumer<byte[]> onEncodedAudio) {
        cancelled = false;
        Resolved resolved = resolve(profile);
        EdgeTtsCredentials credential;
        try {
            credential = credentials.get();
        } catch (RuntimeException failure) {
            throw EdgeTtsFailures.fromThrowable(failure);
        }
        String connectionId = UUID.randomUUID().toString().replace("-", "");
        String url = EdgeTtsProtocol.connectionUrl(
                resolved.endpoint(), credential.token(), credential.version(), connectionId);

        EdgeTtsConnection connection = null;
        try {
            connection = connectionFactory.open(url, EdgeTtsProtocol.userAgent(credential.version()),
                    profile.timeoutMillis());
            current = connection;
            connection.send(EdgeTtsProtocol.configFrame());
            connection.send(EdgeTtsProtocol.ssmlFrame(connectionId, resolved.voice(), text));
            connection.receive(frame -> {
                if (cancelled) {
                    return;
                }
                byte[] audio = EdgeTtsProtocol.audioPayload(frame);
                if (audio.length > 0) {
                    onEncodedAudio.accept(audio);
                }
            });
        } catch (TtsFailureException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw EdgeTtsFailures.fromThrowable(failure);
        } finally {
            current = null;
            if (connection != null) {
                connection.close();
            }
        }
    }

    @Override
    public void cancel() {
        cancelled = true;
        EdgeTtsConnection connection = current;
        if (connection != null) {
            connection.close();
        }
    }

    @Override
    public void close() {
        cancel();
    }

    /** Endpoint and voice after applying the profile's overrides to the provider's defaults. */
    private static Resolved resolve(TtsCloudProfile profile) {
        Optional<TtsCloudProviders.Provider> provider = TtsCloudProviders.byId(profile.providerId());
        String endpoint = profile.hasEndpointOverride()
                ? profile.endpoint()
                : provider.map(TtsCloudProviders.Provider::defaultEndpoint).orElse("");
        String voice = profile.hasVoiceOverride()
                ? profile.voiceId()
                : provider.map(TtsCloudProviders.Provider::defaultVoice).orElse("");
        if (endpoint.isEmpty()) {
            throw new TtsFailureException(TtsFailureCode.CLOUD_UNAVAILABLE,
                    "no endpoint is configured for cloud provider: " + profile.providerId());
        }
        if (voice.isEmpty()) {
            throw new TtsFailureException(TtsFailureCode.INVALID_REQUEST,
                    "cloud provider has no usable voice: " + profile.providerId());
        }
        return new Resolved(endpoint, voice);
    }

    private record Resolved(String endpoint, String voice) {
    }
}