package com.rheinmetal.tianshu.function.tts.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TtsStreamChunkTest {
    @Test
    void streamIdentityFallsBackToEnvelopeId() {
        TtsStreamChunk chunk = new TtsStreamChunk(" ", "env-1", "trace-1", null, null, null, null, false);

        assertEquals("env-1", chunk.streamId());
        assertEquals("env-1", chunk.envelopeId());
        assertEquals("trace-1", chunk.traceId());
        assertEquals("", chunk.text());
        assertEquals(TtsRequestSource.AX, chunk.source());
        assertEquals(TtsPlaybackPolicy.QUEUE, chunk.playbackPolicy());
    }

    @Test
    void streamIdentityFallsBackToTraceId() {
        TtsStreamChunk chunk = new TtsStreamChunk(" ", " ", "trace-1", "text", null, null, null, false);

        assertEquals("trace-1", chunk.streamId());
        assertEquals("trace-1", chunk.envelopeId());
        assertEquals("trace-1", chunk.traceId());
    }

    @Test
    void streamIdentityGeneratesWhenAllIdsAreBlank() {
        TtsStreamChunk chunk = new TtsStreamChunk(" ", " ", " ", "text", null, null, null, false);

        assertFalse(chunk.streamId().isBlank());
        assertEquals(chunk.streamId(), chunk.envelopeId());
        assertEquals(chunk.streamId(), chunk.traceId());
    }
}
