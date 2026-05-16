package com.rheinmetal.tianshu.function.tts.runtime;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsStreamRegistryTest {
    @Test
    void appendIgnoresNullChunk() {
        TtsStreamRegistry registry = new TtsStreamRegistry();

        Optional<String> segment = registry.append(null);

        assertFalse(segment.isPresent());
        assertEquals(0, registry.activeStreamCount());
    }

    @Test
    void lastChunkFlushesAndRemovesStream() {
        TtsStreamRegistry registry = new TtsStreamRegistry();
        TtsStreamChunk chunk = new TtsStreamChunk("stream-1", "env-1", "trace-1", "你好世界", TtsRequestSource.ASSISTANT, TtsPlaybackPolicy.QUEUE, TtsVoiceProfile.defaults(), true);

        Optional<String> segment = registry.append(chunk);

        assertTrue(segment.isPresent());
        assertEquals("你好世界", segment.get());
        assertEquals(0, registry.activeStreamCount());
    }

    @Test
    void cancelClearsBufferedStream() {
        TtsStreamRegistry registry = new TtsStreamRegistry();
        registry.append(new TtsStreamChunk("stream-1", "env-1", "trace-1", "还没有结束", TtsRequestSource.ASSISTANT, TtsPlaybackPolicy.QUEUE, TtsVoiceProfile.defaults(), false));

        registry.cancel("stream-1");

        assertEquals(0, registry.activeStreamCount());
    }

    @Test
    void clearRemovesAllBufferedStreams() {
        TtsStreamRegistry registry = new TtsStreamRegistry();
        registry.append(new TtsStreamChunk("stream-1", "env-1", "trace-1", "第一段", TtsRequestSource.ASSISTANT, TtsPlaybackPolicy.QUEUE, TtsVoiceProfile.defaults(), false));
        registry.append(new TtsStreamChunk("stream-2", "env-2", "trace-2", "第二段", TtsRequestSource.ASSISTANT, TtsPlaybackPolicy.QUEUE, TtsVoiceProfile.defaults(), false));

        registry.clear();

        assertEquals(0, registry.activeStreamCount());
    }
}
