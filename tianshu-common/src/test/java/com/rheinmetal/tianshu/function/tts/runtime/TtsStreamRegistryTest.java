package com.rheinmetal.tianshu.function.tts.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TtsStreamRegistryTest {
    @Test
    void appendIgnoresNullChunk() {
        TtsStreamRegistry registry = new TtsStreamRegistry();

        List<String> segments = registry.append(null);

        assertEquals(List.of(), segments);
        assertEquals(0, registry.activeStreamCount());
    }

    @Test
    void lastChunkFlushesAndRemovesStream() {
        TtsStreamRegistry registry = new TtsStreamRegistry();
        TtsStreamChunk chunk = new TtsStreamChunk("stream-1", "env-1", "trace-1", "hello world", TtsRequestSource.AX, TtsPlaybackPolicy.QUEUE, TtsVoiceProfile.defaults(), true);

        List<String> segments = registry.append(chunk);

        assertEquals(List.of("hello world"), segments);
        assertEquals(0, registry.activeStreamCount());
    }

    @Test
    void lastChunkReturnsBoundarySegmentsAndTailInOrder() {
        TtsStreamRegistry registry = new TtsStreamRegistry();
        TtsStreamChunk chunk = new TtsStreamChunk("stream-1", "env-1", "trace-1", "This sentence is definitely long enough! Tail", TtsRequestSource.AX, TtsPlaybackPolicy.QUEUE, TtsVoiceProfile.defaults(), true);

        List<String> segments = registry.append(chunk);

        assertEquals(List.of("This sentence is definitely long enough!", "Tail"), segments);
        assertEquals(0, registry.activeStreamCount());
    }

    @Test
    void cancelClearsBufferedStream() {
        TtsStreamRegistry registry = new TtsStreamRegistry();
        registry.append(new TtsStreamChunk("stream-1", "env-1", "trace-1", "partial text", TtsRequestSource.AX, TtsPlaybackPolicy.QUEUE, TtsVoiceProfile.defaults(), false));

        registry.cancel("stream-1");

        assertEquals(0, registry.activeStreamCount());
    }

    @Test
    void clearRemovesAllBufferedStreams() {
        TtsStreamRegistry registry = new TtsStreamRegistry();
        registry.append(new TtsStreamChunk("stream-1", "env-1", "trace-1", "first partial", TtsRequestSource.AX, TtsPlaybackPolicy.QUEUE, TtsVoiceProfile.defaults(), false));
        registry.append(new TtsStreamChunk("stream-2", "env-2", "trace-2", "second partial", TtsRequestSource.AX, TtsPlaybackPolicy.QUEUE, TtsVoiceProfile.defaults(), false));

        registry.clear();

        assertEquals(0, registry.activeStreamCount());
    }
}
