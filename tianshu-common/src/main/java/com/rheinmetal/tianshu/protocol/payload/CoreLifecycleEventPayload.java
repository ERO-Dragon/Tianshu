package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;
import com.rheinmetal.tianshu.protocol.lifecycle.CoreLifecycleEventType;

import java.util.Map;

public record CoreLifecycleEventPayload(CoreLifecycleEventType eventType, long timestampMillis, Map<String, String> attributes) implements ITianshuPayload {
    public CoreLifecycleEventPayload {
        if (eventType == null) {
            throw new IllegalArgumentException("eventType cannot be null");
        }
        if (timestampMillis <= 0L) timestampMillis = System.currentTimeMillis();
        attributes = attributes == null || attributes.isEmpty() ? Map.of() : Map.copyOf(attributes);
    }
}
