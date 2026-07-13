package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.function.tts.text.TtsStreamBuffer;
import com.rheinmetal.tianshu.text.SentenceSegmenter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class TtsStreamRegistry {
    private static final long CANCEL_BARRIER_TTL_MILLIS = 60_000L;

    private final Map<String, TtsStreamBuffer> streams = new ConcurrentHashMap<>();
    private final Map<String, Long> cancelBarriers = new ConcurrentHashMap<>();

    public List<String> append(TtsStreamChunk chunk) {
        if (chunk == null) {
            return List.of();
        }
        if (isCancelled(chunk.streamId())) {
            if (chunk.last()) {
                clearCancelBarrier(chunk.streamId());
            }
            return List.of();
        }
        TtsStreamBuffer buffer = streams.computeIfAbsent(chunk.streamId(), ignored -> new TtsStreamBuffer(new SentenceSegmenter()));
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
        String normalized = normalize(streamId);
        TtsStreamBuffer buffer = streams.remove(normalized);
        if (buffer != null) {
            buffer.clear();
        }
        cancelBarriers.put(normalized, System.currentTimeMillis() + CANCEL_BARRIER_TTL_MILLIS);
    }

    public void clear() {
        streams.values().forEach(TtsStreamBuffer::clear);
        streams.clear();
        cancelBarriers.clear();
    }

    public int activeStreamCount() {
        return streams.size();
    }

    int cancelBarrierCount() {
        pruneExpiredCancelBarriers();
        return cancelBarriers.size();
    }

    private boolean isCancelled(String streamId) {
        String normalized = normalize(streamId);
        Long deadline = cancelBarriers.get(normalized);
        if (deadline == null) {
            return false;
        }
        if (deadline < System.currentTimeMillis()) {
            cancelBarriers.remove(normalized, deadline);
            return false;
        }
        return true;
    }

    private void clearCancelBarrier(String streamId) {
        cancelBarriers.remove(normalize(streamId));
    }

    private void pruneExpiredCancelBarriers() {
        long now = System.currentTimeMillis();
        cancelBarriers.entrySet().removeIf(entry -> entry.getValue() < now);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
