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
    /** A chat line is a world event with a speaker, not a second payload kind. */
    public static final String EVENT_CHAT_MESSAGE = "chat_message";

    public static final String VALUE_SENDER_ID = "senderId";
    public static final String VALUE_SENDER_NAME = "senderName";
    public static final String VALUE_TEXT = "text";

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

    /**
     * One chat line as a world event.
     *
     * <p>Chat used to travel as its own payload, which meant two kinds of "something happened in the world"
     * for consumers to switch on. It is a kind now: the event type says what happened, the values carry who
     * spoke and what was said.</p>
     */
    public static PresenceWorldEventPayload chatMessage(String senderId, String senderName, String messageText) {
        return new PresenceWorldEventPayload(
                null,
                EVENT_CHAT_MESSAGE,
                senderId,
                "",
                System.currentTimeMillis(),
                Map.of(
                        VALUE_SENDER_ID, senderId == null ? "" : senderId,
                        VALUE_SENDER_NAME, senderName == null ? "" : senderName,
                        VALUE_TEXT, messageText == null ? "" : messageText
                )
        );
    }

    private static String clean(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
