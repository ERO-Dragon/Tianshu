package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;
import com.rheinmetal.tianshu.protocol.Priority;

public record TtsPlaybackStatusPayload(
        String requestId,
        String traceId,
        String source,
        TtsPlaybackPhase phase,
        Priority priority,
        String message,
        long occurredAtMillis
) implements ITianshuPayload {
    public TtsPlaybackStatusPayload {
        requestId = requestId == null ? "" : requestId.trim();
        traceId = traceId == null ? "" : traceId.trim();
        source = source == null ? "unknown" : source.trim();
        phase = phase == null ? TtsPlaybackPhase.ACCEPTED : phase;
        priority = priority == null ? Priority.NORMAL : priority;
        message = message == null ? "" : message.trim();
        occurredAtMillis = occurredAtMillis > 0L ? occurredAtMillis : System.currentTimeMillis();
    }
}
