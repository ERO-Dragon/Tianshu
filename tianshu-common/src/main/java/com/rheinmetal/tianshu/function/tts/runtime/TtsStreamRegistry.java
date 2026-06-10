package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.function.tts.text.TtsSentenceSegmenter;
import com.rheinmetal.tianshu.function.tts.text.TtsStreamBuffer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class TtsStreamRegistry {
    private final Map<String, TtsStreamBuffer> streams = new ConcurrentHashMap<>();

    public List<String> append(TtsStreamChunk chunk) {
        if (chunk == null) {
            return List.of();
        }
        TtsStreamBuffer buffer = streams.computeIfAbsent(chunk.streamId(), ignored -> new TtsStreamBuffer(new TtsSentenceSegmenter()));
        List<String> segments = new ArrayList<>(buffer.appendSegments(chunk.text()));
        if (chunk.last()) {
            buffer.flush().ifPresent(segments::add);
            streams.remove(chunk.streamId());
        }
        return List.copyOf(segments);
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
