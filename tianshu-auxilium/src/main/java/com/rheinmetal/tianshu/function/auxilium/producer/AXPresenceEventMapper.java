package com.rheinmetal.tianshu.function.auxilium.producer;

import com.rheinmetal.tianshu.function.auxilium.event.AXEventText;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventTexts;
import com.rheinmetal.tianshu.protocol.payload.PresenceWorldEventPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns one presence world event into one line of the event layer.
 *
 * <p>The fact belongs to the presence module, the wording and the tier belong to this one: this table answers only
 * "which kind is this event, and which of its values words the line". Whether that line is spoken, deferred or
 * silently filed is decided by the tier table and the attention list, exactly as for a built-in event.</p>
 *
 * <p>A type that is not mapped here is not an event of its own — the caller keeps such a fact in the attached memory
 * instead. One fact therefore has one landing point, never two.</p>
 *
 * <p>Presence ships a second's worth of one kind as one payload built from groups, so what leaves here is a
 * {@link Batch}: one "count + name" fragment per group plus the summed count. The fragments are rendered one level
 * deep and joined by the service, which is what keeps a batch from running into the renderer's nesting cap.</p>
 */
public final class AXPresenceEventMapper {

    /** Language key of one "count + name" fragment inside a batch line. */
    private static final String BATCH_ENTRY_KEY = "tianshu.ax.event.batch.entry";

    /** Presence event type to the kind it becomes and the values the sentence may take, first non-blank wins. */
    private static final Map<String, Rule> RULES = Map.ofEntries(
            Map.entry(PresenceWorldEventPayload.EVENT_ADVANCEMENT_UNLOCKED,
                    new Rule("tianshu:advancement", List.of(PresenceWorldEventPayload.VALUE_TITLE))),
            Map.entry(PresenceWorldEventPayload.EVENT_ITEM_CRAFTED,
                    new Rule("tianshu:item_crafted", List.of(
                            PresenceWorldEventPayload.VALUE_ITEM_NAME,
                            PresenceWorldEventPayload.VALUE_ITEM_ID))),
            Map.entry(PresenceWorldEventPayload.EVENT_PLAYER_DEATH,
                    new Rule("tianshu:death", List.of(PresenceWorldEventPayload.VALUE_DEATH_MESSAGE))),
            Map.entry(PresenceWorldEventPayload.EVENT_MOB_KILLED,
                    new Rule("tianshu:mob_killed", List.of(
                            PresenceWorldEventPayload.VALUE_ENTITY_NAME,
                            PresenceWorldEventPayload.VALUE_ENTITY_ID))),
            Map.entry(PresenceWorldEventPayload.EVENT_FISHING_CATCH,
                    new Rule("tianshu:fishing_catch", List.of(
                            PresenceWorldEventPayload.VALUE_ITEM_NAME,
                            PresenceWorldEventPayload.VALUE_ITEM_ID))),
            Map.entry(PresenceWorldEventPayload.EVENT_TAMED,
                    new Rule("tianshu:tamed", List.of(
                            PresenceWorldEventPayload.VALUE_ENTITY_NAME,
                            PresenceWorldEventPayload.VALUE_ENTITY_ID))),
            Map.entry(PresenceWorldEventPayload.EVENT_BLOCK_BROKEN,
                    new Rule("tianshu:block_break", List.of(
                            PresenceWorldEventPayload.VALUE_BLOCK_NAME,
                            PresenceWorldEventPayload.VALUE_BLOCK_ID))),
            Map.entry(PresenceWorldEventPayload.EVENT_BLOCK_PLACED,
                    new Rule("tianshu:block_place", List.of(
                            PresenceWorldEventPayload.VALUE_BLOCK_NAME,
                            PresenceWorldEventPayload.VALUE_BLOCK_ID))),
            Map.entry(PresenceWorldEventPayload.EVENT_CONTAINER_OPEN,
                    new Rule("tianshu:container_open", List.of())),
            Map.entry(PresenceWorldEventPayload.EVENT_MOUNT,
                    new Rule("tianshu:mount", List.of(
                            PresenceWorldEventPayload.VALUE_ENTITY_NAME,
                            PresenceWorldEventPayload.VALUE_ENTITY_ID))),
            Map.entry(PresenceWorldEventPayload.EVENT_DISMOUNT,
                    new Rule("tianshu:dismount", List.of(
                            PresenceWorldEventPayload.VALUE_ENTITY_NAME,
                            PresenceWorldEventPayload.VALUE_ENTITY_ID))),
            Map.entry(PresenceWorldEventPayload.EVENT_DIMENSION_CHANGED,
                    new Rule("tianshu:dimension_enter", List.of(
                            PresenceWorldEventPayload.VALUE_DIMENSION_NAME,
                            PresenceWorldEventPayload.VALUE_DIMENSION))),
            Map.entry(PresenceWorldEventPayload.EVENT_EFFECT_GAINED,
                    new Rule("tianshu:effect_gained", List.of(PresenceWorldEventPayload.VALUE_EFFECT_NAME))),
            Map.entry(PresenceWorldEventPayload.EVENT_EFFECT_EXPIRED,
                    new Rule("tianshu:effect_expired", List.of(PresenceWorldEventPayload.VALUE_EFFECT_NAME))),
            Map.entry(PresenceWorldEventPayload.EVENT_DAMAGE_TAKEN,
                    new Rule("tianshu:damage_taken", List.of(PresenceWorldEventPayload.VALUE_AMOUNT))),
            Map.entry(PresenceWorldEventPayload.EVENT_EXP_LEVEL,
                    new Rule("tianshu:exp_level", List.of(PresenceWorldEventPayload.VALUE_LEVEL))),
            Map.entry(PresenceWorldEventPayload.EVENT_FRIEND_JOIN,
                    new Rule("tianshu:friend_join", List.of(PresenceWorldEventPayload.VALUE_PLAYER_NAME))),
            Map.entry(PresenceWorldEventPayload.EVENT_FRIEND_LEAVE,
                    new Rule("tianshu:friend_leave", List.of(PresenceWorldEventPayload.VALUE_PLAYER_NAME))),
            Map.entry(PresenceWorldEventPayload.EVENT_ITEM_PICKUP,
                    new Rule("tianshu:pickup", List.of(
                            PresenceWorldEventPayload.VALUE_ITEM_NAME,
                            PresenceWorldEventPayload.VALUE_ITEM_ID))),
            Map.entry(PresenceWorldEventPayload.EVENT_TRADE,
                    new Rule("tianshu:trade", List.of(
                            PresenceWorldEventPayload.VALUE_ITEM_NAME,
                            PresenceWorldEventPayload.VALUE_ITEM_ID))),
            Map.entry(PresenceWorldEventPayload.EVENT_BOSS_ENGAGE,
                    new Rule("tianshu:boss_engage", List.of(PresenceWorldEventPayload.VALUE_ENTITY_NAME))),
            Map.entry(PresenceWorldEventPayload.EVENT_RESPAWN,
                    new Rule("tianshu:respawn", List.of(PresenceWorldEventPayload.VALUE_DIMENSION_NAME)))
    );

    private AXPresenceEventMapper() {
    }

    /** One payload as one line: the kind's template, one "count + name" fragment per group, and the summed count. */
    public record Batch(String kind, String lineKey, List<AXEventText> parts, int totalCount) {
    }

    /** {@link Batch} for this payload, or empty when its event type is not an event of its own. */
    public static Optional<Batch> batch(PresenceWorldEventPayload payload) {
        if (payload == null || payload.occurrences() == null || payload.occurrences().isEmpty()) {
            return Optional.empty();
        }
        Rule rule = RULES.get(payload.eventType());
        if (rule == null) {
            return Optional.empty();
        }
        String lineKey = AXEventTexts.LINE_PREFIX + AXEventTexts.kindSegment(rule.kind());
        List<AXEventText> parts = new ArrayList<>(payload.occurrences().size());
        int total = 0;
        for (PresenceWorldEventPayload.Occurrence occurrence : payload.occurrences()) {
            String detail = rule.valueKeys().stream()
                    .map(occurrence.values()::get)
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .findFirst()
                    .orElse("");
            if (detail.isEmpty()) {
                continue;
            }
            parts.add(AXEventText.of(BATCH_ENTRY_KEY, Integer.toString(occurrence.count()), detail));
            total += occurrence.count();
        }
        if (parts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Batch(rule.kind(), lineKey, parts, total));
    }

    /** One row of the table: the kind, and which values of the event may word the line, in preference order. */
    private record Rule(String kind, List<String> valueKeys) {
    }
}
