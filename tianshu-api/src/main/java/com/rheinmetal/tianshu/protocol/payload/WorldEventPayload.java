package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

import java.util.Map;

/**
 * One world event, as the platform observed it.
 *
 * <p>This is the raw shape: a single occurrence, produced by the adapter (client hooks and the optional server
 * component, which both end in the same client-side entry). It carries no window, no grouping and no consumer
 * semantics — aggregation, rate limiting and re-publication belong to the module that consumes this stream.</p>
 *
 * <p>The vocabulary is {@link WorldEventTypes}: the event type says what happened, the values carry the fields that
 * kind needs. A blank value is dropped so "absent" stays distinguishable from "empty".</p>
 */
public record WorldEventPayload(
        String eventType,
        String playerId,
        String dimensionId,
        Map<String, String> values,
        long occurredAtMillis
) implements ITianshuPayload {
    public static final String TOPIC = "GAME.WORLD_EVENT";

    public WorldEventPayload {
        eventType = clean(eventType, "world_event");
        playerId = clean(playerId, "");
        dimensionId = clean(dimensionId, "");
        values = values == null ? Map.of() : Map.copyOf(values.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank()
                        && entry.getValue() != null && !entry.getValue().isBlank())
                .collect(java.util.stream.Collectors.toMap(
                        entry -> entry.getKey().trim(),
                        entry -> entry.getValue().trim(),
                        (first, second) -> second
                )));
        if (occurredAtMillis <= 0L) {
            occurredAtMillis = System.currentTimeMillis();
        }
    }

    /** One chat line as a world event; chat is a kind, not a second payload shape. */
    public static WorldEventPayload chatMessage(String playerId, String senderId, String senderName, String messageText) {
        return new WorldEventPayload(
                WorldEventTypes.EVENT_CHAT_MESSAGE,
                playerId,
                "",
                Map.of(
                        WorldEventTypes.VALUE_SENDER_ID, senderId == null ? "" : senderId,
                        WorldEventTypes.VALUE_SENDER_NAME, senderName == null ? "" : senderName,
                        WorldEventTypes.VALUE_TEXT, messageText == null ? "" : messageText
                ),
                System.currentTimeMillis()
        );
    }

    private static String clean(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}