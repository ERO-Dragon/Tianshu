package com.rheinmetal.tianshu.function.echo.synthesis.cloud;

import com.rheinmetal.tianshu.function.echo.runtime.TtsFailureCode;
import com.rheinmetal.tianshu.function.echo.runtime.TtsFailureException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transport's job is ordering and shaping: resolve the profile, open one connection, send the two frames
 * in order, strip the binary header and stop at the turn boundary. None of that needs a socket.
 */
class EdgeTtsTransportTest {

    @Test
    void theTwoFramesAreSentInOrderAndAudioIsUnwrapped() {
        FakeFactory factory = new FakeFactory();
        factory.connection.audio = List.of(payload(new byte[]{1, 2}), payload(new byte[]{3}));
        EdgeTtsTransport transport = transport(factory, "tok", "2-1");

        List<byte[]> audio = new ArrayList<>();
        transport.speak(profile("", ""), "hello", audio::add);

        assertEquals(2, factory.connection.sent.size(), "config then ssml");
        assertTrue(factory.connection.sent.get(0).contains("Path:speech.config"), factory.connection.sent.get(0));
        assertTrue(factory.connection.sent.get(1).contains("Path:ssml"), factory.connection.sent.get(1));
        assertTrue(factory.connection.sent.get(1).contains("hello"), factory.connection.sent.get(1));
        assertArrayEquals(new byte[]{1, 2}, audio.get(0));
        assertArrayEquals(new byte[]{3}, audio.get(1));
        assertTrue(factory.connection.closed, "the connection has to be closed when the turn ends");
    }

    @Test
    void theUrlCarriesTheProviderCredentialAndAConnectionId() {
        FakeFactory factory = new FakeFactory();
        EdgeTtsTransport transport = transport(factory, "TOKEN", "2-143.0.3650.75");

        transport.speak(profile("", ""), "hello", chunk -> { });

        String url = factory.openedUrl;
        assertTrue(url.startsWith(TtsCloudProviders.defaultProvider().defaultEndpoint() + "?"), url);
        assertTrue(url.contains("Sec-MS-GEC=TOKEN"), url);
        assertTrue(url.contains("Sec-MS-GEC-Version=2-143.0.3650.75"), url);
        assertTrue(url.contains("ConnectionId="), url);
    }

    @Test
    void defaultsComeFromTheProviderWhenTheProfileDoesNotOverride() {
        FakeFactory factory = new FakeFactory();
        EdgeTtsTransport transport = transport(factory, "t", "2-143.0.3650.75");

        transport.speak(profile("", ""), "hello", chunk -> { });

        assertTrue(factory.openedUrl.startsWith(TtsCloudProviders.defaultProvider().defaultEndpoint()), factory.openedUrl);
        assertTrue(factory.connection.sent.get(1).contains("XiaoxiaoNeural"),
                factory.connection.sent.get(1));
    }

    @Test
    void overridesReplaceTheDefaults() {
        FakeFactory factory = new FakeFactory();
        EdgeTtsTransport transport = transport(factory, "t", "2-143.0.3650.75");

        transport.speak(profile("wss://custom.invalid/v1", "zh-CN-YunxiNeural"), "hello", chunk -> { });

        assertTrue(factory.openedUrl.startsWith("wss://custom.invalid/v1?"), factory.openedUrl);
        assertTrue(factory.connection.sent.get(1).contains("YunxiNeural"),
                factory.connection.sent.get(1));
    }

    @Test
    void aCredentialFailureIsReportedAsACloudFailure() {
        EdgeTtsTransport transport = new EdgeTtsTransport(
                () -> {
                    throw new IllegalStateException("clock skew");
                },
                new FakeFactory());

        TtsFailureException thrown = assertThrows(TtsFailureException.class,
                () -> transport.speak(profile("", ""), "hello", chunk -> { }));

        assertEquals(TtsFailureCode.CLOUD_UNAVAILABLE, thrown.code());
    }

    @Test
    void aConnectionFailureKeepsItsCode() {
        FakeFactory factory = new FakeFactory();
        factory.failure = new TtsFailureException(TtsFailureCode.CLOUD_RATE_LIMITED, "429");
        EdgeTtsTransport transport = new EdgeTtsTransport(() -> new EdgeTtsCredentials("t", "2-143.0.3650.75"), factory);

        TtsFailureException thrown = assertThrows(TtsFailureException.class,
                () -> transport.speak(profile("", ""), "hello", chunk -> { }));

        assertEquals(TtsFailureCode.CLOUD_RATE_LIMITED, thrown.code());
    }

    @Test
    void cancelClosesTheConnectionAndDropsFurtherAudio() {
        FakeFactory factory = new FakeFactory();
        EdgeTtsTransport transport = transport(factory, "t", "2-143.0.3650.75");
        List<byte[]> audio = new ArrayList<>();
        factory.connection.beforeAudio = transport::cancel;

        transport.speak(profile("", ""), "hello", audio::add);

        assertTrue(factory.connection.closed);
        assertTrue(audio.isEmpty(), "audio after cancel must not reach the caller");
    }

    @Test
    void anUnknownProviderWithoutAnEndpointFailsBeforeOpening() {
        FakeFactory factory = new FakeFactory();
        EdgeTtsTransport transport = transport(factory, "t", "2-143.0.3650.75");

        TtsFailureException thrown = assertThrows(TtsFailureException.class,
                () -> transport.speak(new TtsCloudProfile("unknown", "", "", 1_000L), "hello", chunk -> { }));

        assertEquals(TtsFailureCode.CLOUD_UNAVAILABLE, thrown.code());
        assertFalse(factory.opened, "no connection may be attempted without an endpoint");
    }

    private static EdgeTtsTransport transport(EdgeTtsConnectionFactory factory, String token, String version) {
        return new EdgeTtsTransport(() -> new EdgeTtsCredentials(token, version), factory);
    }

    private static TtsCloudProfile profile(String endpoint, String voice) {
        return new TtsCloudProfile(TtsCloudProviders.EDGE, endpoint, voice, 1_000L);
    }

    private static byte[] payload(byte[] audio) {
        byte[] header = "Path:audio\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] frame = new byte[2 + header.length + audio.length];
        frame[0] = (byte) (header.length >> 8);
        frame[1] = (byte) header.length;
        System.arraycopy(header, 0, frame, 2, header.length);
        System.arraycopy(audio, 0, frame, 2 + header.length, audio.length);
        return frame;
    }

    private static final class FakeFactory implements EdgeTtsConnectionFactory {
        private final FakeConnection connection = new FakeConnection();
        private String openedUrl;
        private boolean opened;
        private TtsFailureException failure;

        @Override
        public EdgeTtsConnection open(String url, String userAgent, long timeoutMillis) {
            openedUrl = url;
            opened = true;
            if (failure != null) {
                throw failure;
            }
            return connection;
        }
    }

    private static final class FakeConnection implements EdgeTtsConnection {
        private final List<String> sent = new ArrayList<>();
        private List<byte[]> audio = List.of();
        private boolean closed;
        private Runnable beforeAudio;

        @Override
        public void send(String frame) {
            sent.add(frame);
        }

        @Override
        public void receive(Consumer<byte[]> onBinary) {
            for (byte[] frame : audio) {
                if (beforeAudio != null) {
                    beforeAudio.run();
                }
                onBinary.accept(frame);
            }
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    void failureMappingSeparatesTheReasonsAPlayerCanActOn() {
        assertEquals(TtsFailureCode.CLOUD_AUTH_FAILED, EdgeTtsFailures.fromStatus(403).code());
        assertEquals(TtsFailureCode.CLOUD_AUTH_FAILED, EdgeTtsFailures.fromStatus(401).code());
        assertEquals(TtsFailureCode.CLOUD_RATE_LIMITED, EdgeTtsFailures.fromStatus(429).code());
        assertEquals(TtsFailureCode.CLOUD_UNAVAILABLE, EdgeTtsFailures.fromStatus(500).code());
        assertEquals(TtsFailureCode.CLOUD_TIMEOUT,
                EdgeTtsFailures.fromThrowable(new HttpTimeoutException("slow")).code());
        assertEquals(TtsFailureCode.CLOUD_TIMEOUT,
                EdgeTtsFailures.fromThrowable(new TimeoutException("slow")).code());
        assertEquals(TtsFailureCode.CLOUD_UNAVAILABLE,
                EdgeTtsFailures.fromThrowable(new IOException("reset")).code());
        assertEquals(TtsFailureCode.CLOUD_RATE_LIMITED,
                EdgeTtsFailures.fromThrowable(new RuntimeException("wrap",
                        new TtsFailureException(TtsFailureCode.CLOUD_RATE_LIMITED, "429"))).code());
    }
}