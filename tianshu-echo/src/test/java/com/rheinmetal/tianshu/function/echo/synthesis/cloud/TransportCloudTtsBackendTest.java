package com.rheinmetal.tianshu.function.echo.synthesis.cloud;

import com.rheinmetal.tianshu.function.echo.runtime.TtsFailureCode;
import com.rheinmetal.tianshu.function.echo.runtime.TtsFailureException;
import com.rheinmetal.tianshu.function.echo.runtime.TtsRequest;
import com.rheinmetal.tianshu.function.echo.runtime.TtsVoiceProfile;
import com.rheinmetal.tianshu.protocol.Priority;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cloud backend's contract: encoded chunks in, decoded PCM out, and a failure that keeps the provider's
 * reason instead of collapsing into "synthesis failed".
 */
class TransportCloudTtsBackendTest {

    private static final TtsCloudProfile PROFILE =
            new TtsCloudProfile("edge", "wss://example.invalid/v1", "zh-CN-XiaoxiaoNeural", 1_000L);

    @Test
    void decodedChunksReachTheSinkInOrder() {
        FakeTransport transport = new FakeTransport(List.of(new byte[]{1}, new byte[]{2}, new byte[]{3}));
        List<byte[]> pcm = new ArrayList<>();
        TransportCloudTtsBackend backend = new TransportCloudTtsBackend(transport, new DoublingDecoder());

        assertTrue(backend.initialize(PROFILE));
        backend.synthesize(request("hello"), pcm::add);

        assertEquals(3, pcm.size());
        assertArrayEquals(new byte[]{1, 1}, pcm.get(0));
        assertArrayEquals(new byte[]{3, 3}, pcm.get(2));
        assertEquals("hello", transport.spokenText);
        assertEquals(PROFILE, transport.spokenProfile);
    }

    @Test
    void aProviderFailureKeepsItsCode() {
        FakeTransport transport = new FakeTransport(List.of());
        transport.failure = new TtsFailureException(TtsFailureCode.CLOUD_RATE_LIMITED, "429");
        TransportCloudTtsBackend backend = new TransportCloudTtsBackend(transport, new DoublingDecoder());
        backend.initialize(PROFILE);

        TtsFailureException thrown = assertThrows(TtsFailureException.class,
                () -> backend.synthesize(request("hello"), chunk -> { }));

        assertEquals(TtsFailureCode.CLOUD_RATE_LIMITED, thrown.code());
    }

    @Test
    void undecodableAudioIsReportedAsAProviderResponseProblem() {
        FakeTransport transport = new FakeTransport(List.of(new byte[]{9}));
        Mp3Decoder brokenDecoder = (encoded, pcmSink) -> {
            throw new IllegalStateException("not mp3");
        };
        TransportCloudTtsBackend backend = new TransportCloudTtsBackend(transport, brokenDecoder);
        backend.initialize(PROFILE);

        TtsFailureException thrown = assertThrows(TtsFailureException.class,
                () -> backend.synthesize(request("hello"), chunk -> { }));

        assertEquals(TtsFailureCode.CLOUD_INVALID_RESPONSE, thrown.code());
    }

    @Test
    void interruptStopsTheTransportAndDropsLaterAudio() {
        FakeTransport transport = new FakeTransport(List.of(new byte[]{1}, new byte[]{2}));
        List<byte[]> pcm = new ArrayList<>();
        TransportCloudTtsBackend backend = new TransportCloudTtsBackend(transport, new DoublingDecoder());
        backend.initialize(PROFILE);
        transport.beforeChunk = () -> backend.interrupt();

        backend.synthesize(request("hello"), pcm::add);

        assertTrue(transport.cancelled, "interrupt has to reach the provider, not only the sink");
        assertTrue(pcm.isEmpty(), "audio arriving after the interrupt must not be played");
    }

    @Test
    void shutdownClosesTheTransportAndMarksTheBackendUninitialized() {
        FakeTransport transport = new FakeTransport(List.of());
        TransportCloudTtsBackend backend = new TransportCloudTtsBackend(transport, new DoublingDecoder());
        backend.initialize(PROFILE);

        backend.shutdown();

        assertTrue(transport.closed);
        assertFalse(backend.isInitialized());
    }

    @Test
    void aBackendWithoutAProfileRefusesToInitialize() {
        TransportCloudTtsBackend backend = new TransportCloudTtsBackend(new FakeTransport(List.of()),
                new DoublingDecoder());

        assertFalse(backend.initialize(null));
        assertFalse(backend.initialize(new TtsCloudProfile("", "", "", 0L)));
        assertFalse(backend.isInitialized());
    }

    @Test
    void speakingBeforeInitializeFailsInsteadOfOpeningASocket() {
        FakeTransport transport = new FakeTransport(List.of(new byte[]{1}));
        TransportCloudTtsBackend backend = new TransportCloudTtsBackend(transport, new DoublingDecoder());

        TtsFailureException thrown = assertThrows(TtsFailureException.class,
                () -> backend.synthesize(request("hello"), chunk -> { }));

        assertEquals(TtsFailureCode.SYNTHESIS_ENGINE_UNAVAILABLE, thrown.code());
        assertEquals(0, transport.speakCalls);
    }

    @Test
    void emptyTextFailsBeforeTheProviderIsCharged() {
        FakeTransport transport = new FakeTransport(List.of());
        TransportCloudTtsBackend backend = new TransportCloudTtsBackend(transport, new DoublingDecoder());
        backend.initialize(PROFILE);

        TtsFailureException thrown = assertThrows(TtsFailureException.class,
                () -> backend.synthesize(request("   "), chunk -> { }));

        assertEquals(TtsFailureCode.EMPTY_TEXT, thrown.code());
        assertEquals(0, transport.speakCalls);
    }

    @Test
    void theCloudBackendSpeaksAtTheFormatTheEndpointServes() {
        TransportCloudTtsBackend backend = new TransportCloudTtsBackend(new FakeTransport(List.of()),
                new DoublingDecoder());

        assertEquals(24_000, backend.sampleRate());
        assertEquals(1, backend.contextualSentenceLimit(List.of("一", "二")));
    }

    private static TtsRequest request(String text) {
        return new TtsRequest("req", "grp", "env", "trace", text, null, null, Priority.NORMAL,
                TtsVoiceProfile.defaults());
    }

    private static final class DoublingDecoder implements Mp3Decoder {
        @Override
        public void decode(byte[] encoded, Consumer<byte[]> pcmSink) {
            byte value = encoded.length == 0 ? 0 : encoded[0];
            pcmSink.accept(new byte[]{value, value});
        }
    }

    private static final class FakeTransport implements CloudTtsTransport {
        private final List<byte[]> chunks;
        private final List<byte[]> emitted = new ArrayList<>();
        private TtsCloudProfile spokenProfile;
        private String spokenText;
        private int speakCalls;
        private volatile boolean cancelled;
        private boolean closed;
        private TtsFailureException failure;
        private Runnable beforeChunk;

        private FakeTransport(List<byte[]> chunks) {
            this.chunks = chunks;
        }

        @Override
        public void speak(TtsCloudProfile profile, String text, Consumer<byte[]> onEncodedAudio) {
            speakCalls++;
            spokenProfile = profile;
            spokenText = text;
            if (failure != null) {
                throw failure;
            }
            for (byte[] chunk : chunks) {
                if (beforeChunk != null) {
                    beforeChunk.run();
                }
                emitted.add(chunk);
                onEncodedAudio.accept(chunk);
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}