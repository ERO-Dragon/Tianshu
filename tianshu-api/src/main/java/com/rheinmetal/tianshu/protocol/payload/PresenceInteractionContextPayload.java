package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.client.presence.model.PresenceContextSnapshot;
import com.rheinmetal.tianshu.client.presence.model.PresenceScreenKind;
import com.rheinmetal.tianshu.client.presence.model.PresenceTargetSnapshot;
import com.rheinmetal.tianshu.protocol.ITianshuPayload;

import java.util.List;

/**
 * The claim-relevant slice of what the player is doing right now, pushed when it changes.
 *
 * <p>This is not a context snapshot: it carries only the fields a participant's claim conditions read — the
 * crosshair target, the held and equipped items, the interaction keys and the open screen — so that the
 * arbiter can keep a live pre-winner without asking presence for a full capture. Inventory, effects and the
 * world environment stay out of it.</p>
 *
 * <p>{@link #occurredAtMillis} is left out of {@link #sameInteraction(PresenceInteractionContextPayload)}:
 * two pushes that differ only in time describe the same situation and must not cause another publish.</p>
 */
public record PresenceInteractionContextPayload(
        String playerId,
        String dimensionId,
        PresenceScreenKind screenKind,
        String heldItemId,
        List<String> equippedItemIds,
        String crosshairEntityId,
        String crosshairEntityTypeId,
        boolean crosshairHit,
        boolean interactionKeyDown,
        boolean sneaking,
        long occurredAtMillis
) implements ITianshuPayload {
    public PresenceInteractionContextPayload {
        playerId = clean(playerId);
        dimensionId = clean(dimensionId);
        screenKind = screenKind == null ? PresenceScreenKind.NONE : screenKind;
        heldItemId = clean(heldItemId);
        equippedItemIds = equippedItemIds == null ? List.of() : List.copyOf(equippedItemIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList());
        crosshairEntityId = clean(crosshairEntityId);
        crosshairEntityTypeId = clean(crosshairEntityTypeId);
        occurredAtMillis = occurredAtMillis > 0L ? occurredAtMillis : System.currentTimeMillis();
    }

    public static PresenceInteractionContextPayload from(PresenceContextSnapshot snapshot) {
        PresenceTargetSnapshot target = snapshot.crosshairTarget();
        return new PresenceInteractionContextPayload(
                snapshot.playerId(),
                snapshot.dimensionId(),
                snapshot.screenKind(),
                snapshot.heldItemId(),
                snapshot.equippedItemIds(),
                target.entityId(),
                target.entityTypeId(),
                target.present(),
                snapshot.interactionKeyDown(),
                snapshot.sneaking(),
                snapshot.capturedAtMillis()
        );
    }

    /** Whether two pushes describe the same interaction, ignoring when they were captured. */
    public boolean sameInteraction(PresenceInteractionContextPayload other) {
        return other != null
                && playerId.equals(other.playerId())
                && dimensionId.equals(other.dimensionId())
                && screenKind == other.screenKind()
                && heldItemId.equals(other.heldItemId())
                && equippedItemIds.equals(other.equippedItemIds())
                && crosshairEntityId.equals(other.crosshairEntityId())
                && crosshairEntityTypeId.equals(other.crosshairEntityTypeId())
                && crosshairHit == other.crosshairHit()
                && interactionKeyDown == other.interactionKeyDown()
                && sneaking == other.sneaking();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}