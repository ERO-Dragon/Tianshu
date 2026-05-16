package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.function.tts.text.TtsSentenceSegmenter;
import com.rheinmetal.tianshu.function.tts.text.TtsStreamBuffer;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class TtsStreamRegistry {
    private final Map<String, TtsStreamBuffer> streams = new ConcurrentHashMap<>();

    public Optional<String> append(TtsStreamChunk chunk) {
        if (chunk == null) {
            return Optional.empty();
        }
        TtsStreamBuffer buffer = streams.computeIfAbsent(chunk.streamId(), ignored -> new TtsStreamBuffer(new TtsSentenceSegmenter()));
        Optional<String> segment = buffer.append(chunk.text());
        if (chunk.last()) {
            Optional<String> tail = buffer.flush();
            streams.remove(chunk.streamId());
            return tail.isPresent() ? tail : segment;
        }
        return segment;
    }

    public void cancel(String streamId) {
        if (streamId == null || streamId.isBlank()) {
            return;
        }
        TtsStreamBuffer buffer = streams.remove(streamId.trim());
        if (buffer != null) {
            buffer.clear();
        }
    }

    public void clear() {
        streams.values().forEach(TtsStreamBuffer::clear);
        streams.clear();
    }

    public int activeStreamCount() {
        return streams.size();
    }
}
