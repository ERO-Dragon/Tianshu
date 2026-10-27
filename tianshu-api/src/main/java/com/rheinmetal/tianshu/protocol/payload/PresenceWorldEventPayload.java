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
    public static final String EVENT_ADVANCEMENT_UNLOCKED = "advancement_unlocked";
    public static final String EVENT_PLAYER_DEATH = "player_death";
    /** A craft the server watched happen; only the optional server component can report it. */
    public static final String EVENT_ITEM_CRAFTED = "item_crafted";
    /** Facts only a server can observe; the client never sees these on its own. */
    public static final String EVENT_MOB_KILLED = "mob_killed";
    public static final String EVENT_FISHING_CATCH = "fishing_catch";
    public static final String EVENT_TAMED = "tamed";
    public static final String EVENT_BLOCK_BROKEN = "block_broken";
    public static final String EVENT_BLOCK_PLACED = "block_placed";
    public static final String EVENT_CONTAINER_OPEN = "container_open";
    public static final String EVENT_MOUNT = "mount";
    public static final String EVENT_DISMOUNT = "dismount";
    public static final String EVENT_DIMENSION_CHANGED = "dimension_changed";
    public static final String EVENT_EFFECT_GAINED = "effect_gained";
    public static final String EVENT_EFFECT_EXPIRED = "effect_expired";
    public static final String EVENT_RESPAWN = "respawn";
    public static final String EVENT_DAMAGE_TAKEN = "damage_taken";
    public static final String EVENT_EXP_LEVEL = "exp_level";
    public static final String EVENT_FRIEND_JOIN = "friend_join";
    public static final String EVENT_FRIEND_LEAVE = "friend_leave";
    public static final String EVENT_ITEM_PICKUP = "item_pickup";
    public static final String EVENT_TRADE = "trade";
    /** A boss bar appearing on the client's screen; the client is the only place that sees it. */
    public static final String EVENT_BOSS_ENGAGE = "boss_engage";
    /** The effect name a fact refers to, resolved by the server. */
    public static final String VALUE_EFFECT_NAME = "effectName";
    public static final String VALUE_AMOUNT = "amount";
    public static final String VALUE_LEVEL = "level";
    public static final String VALUE_PLAYER_NAME = "playerName";
    /** A chat line is a world event with a speaker, not a second payload kind. */
    public static final String EVENT_CHAT_MESSAGE = "chat_message";

    public static final String VALUE_SENDER_ID = "senderId";
    public static final String VALUE_SENDER_NAME = "senderName";
    public static final String VALUE_TEXT = "text";
    /** Progress facts carry their own id and their localized title; a consumer words the line from these. */
    public static final String VALUE_ADVANCEMENT_ID = "advancementId";
    public static final String VALUE_TITLE = "title";
    /** Craft facts: the registry id, the name the server resolved, and how many were made. */
    public static final String VALUE_ITEM_ID = "itemId";
    public static final String VALUE_ITEM_NAME = "itemName";
    public static final String VALUE_COUNT = "count";
    /** Combat and entity facts: the registry id and the name the server resolved. */
    public static final String VALUE_ENTITY_ID = "entityId";
    public static final String VALUE_ENTITY_NAME = "entityName";
    /** The vanilla death message, already localized by the server, and the dimension a fact happened in. */
    public static final String VALUE_DEATH_MESSAGE = "deathMessage";
    public static final String VALUE_DIMENSION = "dimension";
    /** The dimension a player moved into, resolved to its display name. */
    public static final String VALUE_DIMENSION_NAME = "dimensionName";
    /** Block facts: the registry id and the name the server resolved. */
    public static final String VALUE_BLOCK_ID = "blockId";
    public static final String VALUE_BLOCK_NAME = "blockName";

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
