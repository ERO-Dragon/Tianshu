package com.rheinmetal.tianshu.client.presence;

import java.util.Map;

public record PresenceInteractionEvent(
        PresenceInputKind inputKind,
        PresenceScreenKind screenKind,
        String eventType,
        long occurredAtMillis,
        Map<String, String> attributes
) {
    public PresenceInteractionEvent {
        inputKind = inputKind == null ? PresenceInputKind.NONE : inputKind;
        screenKind = screenKind == null ? PresenceScreenKind.NONE : screenKind;
        eventType = eventType == null ? "" : eventType.trim();
        if (occurredAtMillis <= 0L) {
            occurredAtMillis = System.currentTimeMillis();
        }
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
