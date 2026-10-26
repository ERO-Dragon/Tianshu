package com.rheinmetal.tianshu.function.auxilium.producer;

import com.rheinmetal.tianshu.function.auxilium.event.AXEventDescriptor;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventPayload;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventText;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventTexts;
import com.rheinmetal.tianshu.protocol.payload.PresenceWorldEventPayload;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns one presence world event into one line of the event layer.
 *
 * <p>The fact belongs to the presence module, the wording and the tier belong to this one: this table answers only
 * "which kind is this event, and which of its values goes into the sentence". Whether that line is spoken, deferred
 * or silently filed is decided by the tier table and the attention list, exactly as for a built-in event.</p>
 *
 * <p>A type that is not mapped here is not an event of its own — the caller keeps such a fact in the attached
 * memory instead. One fact therefore has one landing point, never two.</p>
 */
public final class AXPresenceEventMapper {

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
            Map.entry(PresenceWorldEventPayload.EVENT_DIMENSION_CHANGED,
                    new Rule("tianshu:dimension_enter", List.of(
                            PresenceWorldEventPayload.VALUE_DIMENSION_NAME,
                            PresenceWorldEventPayload.VALUE_DIMENSION))),
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
            Map.entry(PresenceWorldEventPayload.EVENT_EFFECT_GAINED,
                    new Rule("tianshu:effect_gained", List.of(PresenceWorldEventPayload.VALUE_EFFECT_NAME))),
            Map.entry(PresenceWorldEventPayload.EVENT_EFFECT_EXPIRED,
                    new Rule("tianshu:effect_expired", List.of(PresenceWorldEventPayload.VALUE_EFFECT_NAME))),
            Map.entry(PresenceWorldEventPayload.EVENT_EXP_LEVEL,
                    new Rule("tianshu:exp_level", List.of(PresenceWorldEventPayload.VALUE_LEVEL))),
            Map.entry(PresenceWorldEventPayload.EVENT_FRIEND_JOIN,
                    new Rule("tianshu:friend_join", List.of(PresenceWorldEventPayload.VALUE_PLAYER_NAME))),
            Map.entry(PresenceWorldEventPayload.EVENT_FRIEND_LEAVE,
                    new Rule("tianshu:friend_leave", List.of(PresenceWorldEventPayload.VALUE_PLAYER_NAME))),
            Map.entry(PresenceWorldEventPayload.EVENT_RESPAWN,
                    new Rule("tianshu:respawn", List.of(PresenceWorldEventPayload.VALUE_DIMENSION_NAME))),
            Map.entry(PresenceWorldEventPayload.EVENT_DAMAGE_TAKEN,
                    new Rule("tianshu:damage_taken", List.of(PresenceWorldEventPayload.VALUE_AMOUNT))),
            Map.entry(PresenceWorldEventPayload.EVENT_ITEM_PICKUP,
                    new Rule("tianshu:pickup", List.of(
                            PresenceWorldEventPayload.VALUE_ITEM_NAME,
                            PresenceWorldEventPayload.VALUE_ITEM_ID))),
            Map.entry(PresenceWorldEventPayload.EVENT_TRADE,
                    new Rule("tianshu:trade", List.of(
                            PresenceWorldEventPayload.VALUE_ITEM_NAME,
                            PresenceWorldEventPayload.VALUE_ITEM_ID))),
            Map.entry(PresenceWorldEventPayload.EVENT_BOSS_ENGAGE,
                    new Rule("tianshu:boss_engage", List.of(PresenceWorldEventPayload.VALUE_ENTITY_NAME)))
    );

    private AXPresenceEventMapper() {
    }

    /**
     * @param sourceId the envelope's source, recorded for provenance only; it never reaches a decision
     * @return the descriptor the event layer should weigh, or empty when this world event is only a memory fact
     */
    public static Optional<AXEventDescriptor> describe(PresenceWorldEventPayload payload, String sourceId) {
        if (payload == null) {
            return Optional.empty();
        }
        Rule rule = RULES.get(payload.eventType());
        if (rule == null) {
            return Optional.empty();
        }
        String detail = rule.valueKeys().stream()
                .map(payload.values()::get)
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .findFirst()
                .orElse("");
        String lineKey = AXEventTexts.LINE_PREFIX + AXEventTexts.kindSegment(rule.kind());
        AXEventText text = detail.isEmpty() ? AXEventText.of(lineKey) : AXEventText.of(lineKey, detail);
        return Optional.of(AXEventDescriptor.of(rule.kind(), sourceId, AXEventPayload.of(), text));
    }

    /** One row of the table: the kind, and which values of the event may word the line, in preference order. */
    private record Rule(String kind, List<String> valueKeys) {
    }
}