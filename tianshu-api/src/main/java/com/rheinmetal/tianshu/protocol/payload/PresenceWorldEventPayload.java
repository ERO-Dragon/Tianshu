package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public record PresenceWorldEventPayload(
        String eventId,
        String eventType,
        String playerId,
        String dimensionId,
        long occurredAtMillis,
        Map<String, String> values
) implements ITianshuPayload {
    public static final String TOPIC = "PRESENCE.WORLD_EVENT";
    public static final String EVENT_ADVANCEMENT_UNLOCKED = "advancement_unlocked";
    public static final String EVENT_PLAYER_DEATH = "player_death";

    public PresenceWorldEventPayload {
        eventId = clean(eventId, UUID.randomUUID().toString());
        eventType = clean(eventType, "presence.world_event");
        playerId = clean(playerId, "");
        dimensionId = clean(dimensionId, "");
        occurredAtMillis = occurredAtMillis <= 0L ? System.currentTimeMillis() : occurredAtMillis;
        values = values == null ? Map.of() : Map.copyOf(values.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank()
                        && entry.getValue() != null && !entry.getValue().isBlank())
                .collect(Collectors.toMap(
                        entry -> entry.getKey().trim(),
                        entry -> entry.getValue().trim(),
                        (first, second) -> second
                )));
    }

    private static String clean(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
