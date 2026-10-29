package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One kind of world fact, describing a window of occurrences rather than a single moment.
 *
 * <p>Presence never ships one payload per occurrence: a second of mining, a stack of pickups or ten advancements
 * in a row leave as <em>one</em> payload carrying every distinct occurrence group. That is what bounds the
 * protocol cost by "kinds per second" instead of "events per second", and it is why the payload is a list.</p>
 *
 * <p>An {@link Occurrence} is a group: the same values seen {@code count} times inside the window. Two different
 * items picked up in the same second are two occurrence groups, so nothing about <em>what</em> happened is lost;
 * only the repetition is compressed. {@link #values()} keeps the old single-fact reading working by returning the
 * first group, and {@link #totalCount()} answers "how many times did this kind happen".</p>
 *
 * <p>The event vocabulary itself is {@link WorldEventTypes}; the constants below are aliases kept for existing
 * readers.</p>
 */
public record PresenceWorldEventPayload(
        String eventId,
        String eventType,
        String playerId,
        String dimensionId,
        long occurredAtMillis,
        List<Occurrence> occurrences
) implements ITianshuPayload {
    public static final String TOPIC = "PRESENCE.WORLD_EVENT";

    public static final String EVENT_ADVANCEMENT_UNLOCKED = WorldEventTypes.EVENT_ADVANCEMENT_UNLOCKED;
    public static final String EVENT_PLAYER_DEATH = WorldEventTypes.EVENT_PLAYER_DEATH;
    public static final String EVENT_ITEM_CRAFTED = WorldEventTypes.EVENT_ITEM_CRAFTED;
    public static final String EVENT_MOB_KILLED = WorldEventTypes.EVENT_MOB_KILLED;
    public static final String EVENT_FISHING_CATCH = WorldEventTypes.EVENT_FISHING_CATCH;
    public static final String EVENT_TAMED = WorldEventTypes.EVENT_TAMED;
    public static final String EVENT_BLOCK_BROKEN = WorldEventTypes.EVENT_BLOCK_BROKEN;
    public static final String EVENT_BLOCK_PLACED = WorldEventTypes.EVENT_BLOCK_PLACED;
    public static final String EVENT_CONTAINER_OPEN = WorldEventTypes.EVENT_CONTAINER_OPEN;
    public static final String EVENT_MOUNT = WorldEventTypes.EVENT_MOUNT;
    public static final String EVENT_DISMOUNT = WorldEventTypes.EVENT_DISMOUNT;
    public static final String EVENT_DIMENSION_CHANGED = WorldEventTypes.EVENT_DIMENSION_CHANGED;
    public static final String EVENT_EFFECT_GAINED = WorldEventTypes.EVENT_EFFECT_GAINED;
    public static final String EVENT_EFFECT_EXPIRED = WorldEventTypes.EVENT_EFFECT_EXPIRED;
    public static final String EVENT_RESPAWN = WorldEventTypes.EVENT_RESPAWN;
    public static final String EVENT_DAMAGE_TAKEN = WorldEventTypes.EVENT_DAMAGE_TAKEN;
    public static final String EVENT_EXP_LEVEL = WorldEventTypes.EVENT_EXP_LEVEL;
    public static final String EVENT_FRIEND_JOIN = WorldEventTypes.EVENT_FRIEND_JOIN;
    public static final String EVENT_FRIEND_LEAVE = WorldEventTypes.EVENT_FRIEND_LEAVE;
    public static final String EVENT_ITEM_PICKUP = WorldEventTypes.EVENT_ITEM_PICKUP;
    public static final String EVENT_TRADE = WorldEventTypes.EVENT_TRADE;
    public static final String EVENT_BOSS_ENGAGE = WorldEventTypes.EVENT_BOSS_ENGAGE;
    public static final String EVENT_CHAT_MESSAGE = WorldEventTypes.EVENT_CHAT_MESSAGE;

    public static final String VALUE_EFFECT_NAME = WorldEventTypes.VALUE_EFFECT_NAME;
    public static final String VALUE_AMOUNT = WorldEventTypes.VALUE_AMOUNT;
    public static final String VALUE_LEVEL = WorldEventTypes.VALUE_LEVEL;
    public static final String VALUE_PLAYER_NAME = WorldEventTypes.VALUE_PLAYER_NAME;
    public static final String VALUE_SENDER_ID = WorldEventTypes.VALUE_SENDER_ID;
    public static final String VALUE_SENDER_NAME = WorldEventTypes.VALUE_SENDER_NAME;
    public static final String VALUE_TEXT = WorldEventTypes.VALUE_TEXT;
    public static final String VALUE_ADVANCEMENT_ID = WorldEventTypes.VALUE_ADVANCEMENT_ID;
    public static final String VALUE_TITLE = WorldEventTypes.VALUE_TITLE;
    public static final String VALUE_ITEM_ID = WorldEventTypes.VALUE_ITEM_ID;
    public static final String VALUE_ITEM_NAME = WorldEventTypes.VALUE_ITEM_NAME;
    public static final String VALUE_COUNT = WorldEventTypes.VALUE_COUNT;
    public static final String VALUE_ENTITY_ID = WorldEventTypes.VALUE_ENTITY_ID;
    public static final String VALUE_ENTITY_NAME = WorldEventTypes.VALUE_ENTITY_NAME;
    public static final String VALUE_DEATH_MESSAGE = WorldEventTypes.VALUE_DEATH_MESSAGE;
    public static final String VALUE_DIMENSION = WorldEventTypes.VALUE_DIMENSION;
    public static final String VALUE_DIMENSION_NAME = WorldEventTypes.VALUE_DIMENSION_NAME;
    public static final String VALUE_BLOCK_ID = WorldEventTypes.VALUE_BLOCK_ID;
    public static final String VALUE_BLOCK_NAME = WorldEventTypes.VALUE_BLOCK_NAME;

    public PresenceWorldEventPayload {
        eventId = clean(eventId, UUID.randomUUID().toString());
        eventType = clean(eventType, "presence.world_event");
        playerId = clean(playerId, "");
        dimensionId = clean(dimensionId, "");
        occurredAtMillis = occurredAtMillis <= 0L ? System.currentTimeMillis() : occurredAtMillis;
        occurrences = occurrences == null || occurrences.isEmpty()
                ? List.of(new Occurrence(1, Map.of()))
                : List.copyOf(occurrences);
    }

    /**
     * The values of the first occurrence group.
     *
     * <p>Deliberately not a record component: a consumer that only knows the single-fact shape keeps working and
     * sees the representative occurrence, while a consumer that wants the whole window reads {@link #occurrences()}.
     */
    public Map<String, String> values() {
        return occurrences.get(0).values();
    }

    /** How many times this kind happened inside the window, summed over every group. */
    public int totalCount() {
        int total = 0;
        for (Occurrence occurrence : occurrences) {
            total += occurrence.count();
        }
        return total;
    }

    /** The single-fact shape: one occurrence group, seen once. Kept so existing producers and readers do not change. */
    public PresenceWorldEventPayload(
            String eventId,
            String eventType,
            String playerId,
            String dimensionId,
            long occurredAtMillis,
            Map<String, String> values
    ) {
        this(eventId, eventType, playerId, dimensionId, occurredAtMillis, List.of(new Occurrence(1, values)));
    }

    /**
     * One chat line as a world event.
     *
     * <p>Chat used to travel as its own payload, which meant two kinds of "something happened in the world" for
     * consumers to switch on. It is a kind now: the event type says what happened, the values carry who spoke and
     * what was said.</p>
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

    /**
     * One group of identical occurrences inside a window: the values that were observed and how often.
     *
     * <p>Blank keys and values are dropped so "absent" stays distinguishable from "empty", exactly as the
     * single-fact payload always did.</p>
     */
    public record Occurrence(int count, Map<String, String> values) {
        public Occurrence {
            count = Math.max(1, count);
            values = values == null ? Map.of() : Map.copyOf(values.entrySet().stream()
                    .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank()
                            && entry.getValue() != null && !entry.getValue().isBlank())
                    .collect(Collectors.toMap(
                            entry -> entry.getKey().trim(),
                            entry -> entry.getValue().trim(),
                            (first, second) -> second
                    )));
        }
    }

    private static String clean(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}