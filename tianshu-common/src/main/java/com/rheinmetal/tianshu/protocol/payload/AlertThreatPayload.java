package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;
import com.rheinmetal.tianshu.protocol.Priority;

public record AlertThreatPayload(String text, Priority level, boolean interrupt, String source, long occurredAt) implements ITianshuPayload {
    public AlertThreatPayload {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text cannot be blank");
        }
        level = level == null ? Priority.HIGH : level;
        source = source == null || source.isBlank() ? "unknown" : source;
        occurredAt = occurredAt > 0 ? occurredAt : System.currentTimeMillis();
    }
}
