package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;
import com.rheinmetal.tianshu.protocol.lifecycle.ResourceReloadEventType;

import java.util.Map;

public record ResourceReloadEventPayload(ResourceReloadEventType eventType, long timestampMillis, long version, Map<String, String> attributes) implements ITianshuPayload {
    public ResourceReloadEventPayload {
        if (eventType == null) {
            throw new IllegalArgumentException("eventType cannot be null");
        }
        if (timestampMillis <= 0L) timestampMillis = System.currentTimeMillis();
        version = Math.max(0L, version);
        attributes = attributes == null || attributes.isEmpty() ? Map.of() : Map.copyOf(attributes);
    }
}
