package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.client.presence.model.PresenceContextSnapshot;
import com.rheinmetal.tianshu.client.presence.model.PresenceScreenKind;
import com.rheinmetal.tianshu.client.presence.model.PresenceTargetSnapshot;
import com.rheinmetal.tianshu.protocol.ITianshuPayload;

import java.util.List;

/**
 * The turn-relevant slice of what the player is doing right now, pushed when it changes.
 *
 * <p>This is not a context snapshot: it carries only what a turn needs — the crosshair target, the held and
 * equipped items, the interaction keys and the open screen — so that the arbiter can keep a live pre-winner and
 * freeze the same frame when the player speaks, without asking presence for a full capture. Inventory, effects and
 * the world environment stay out of it.</p>
 *
 * <p>The display names and the crosshair distance travel along because a frozen digest is also what the delivery
 * carries to the answering module, and wording needs names rather than ids. No claim condition reads them, and
 * {@link #sameInteraction(PresenceInteractionContextPayload)} ignores them: a renamed world or a player walking one
 * block closer is not a new interaction and must not cause another publish.</p>
 *
 * <p>{@link #occurredAtMillis} is left out of the comparison for the same reason: two pushes that differ only in
 * time describe the same situation.</p>
 */
public record PresenceInteractionContextPayload(
        String playerId,
        String dimensionId,
        String dimensionDisplayName,
        PresenceScreenKind screenKind,
        String heldItemId,
        String heldItemDisplayName,
        List<String> equippedItemIds,
        String crosshairEntityId,
        String crosshairEntityTypeId,
        String crosshairEntityDisplayName,
        double crosshairDistance,
        boolean crosshairHit,
        boolean interactionKeyDown,
        boolean sneaking,
        long occurredAtMillis
) implements ITianshuPayload {
    public PresenceInteractionContextPayload {
        playerId = clean(playerId);
        dimensionId = clean(dimensionId);
        dimensionDisplayName = clean(dimensionDisplayName);
        screenKind = screenKind == null ? PresenceScreenKind.NONE : screenKind;
        heldItemId = clean(heldItemId);
        heldItemDisplayName = clean(heldItemDisplayName);
        equippedItemIds = equippedItemIds == null ? List.of() : List.copyOf(equippedItemIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList());
        crosshairEntityId = clean(crosshairEntityId);
        crosshairEntityTypeId = clean(crosshairEntityTypeId);
        crosshairEntityDisplayName = clean(crosshairEntityDisplayName);
        crosshairDistance = Math.max(0.0D, crosshairDistance);
        occurredAtMillis = occurredAtMillis > 0L ? occurredAtMillis : System.currentTimeMillis();
    }

    public static PresenceInteractionContextPayload from(PresenceContextSnapshot snapshot) {
        PresenceTargetSnapshot target = snapshot.crosshairTarget();
        return new PresenceInteractionContextPayload(
                snapshot.playerId(),
                snapshot.dimensionId(),
                snapshot.dimensionDisplayName(),
                snapshot.screenKind(),
                snapshot.heldItemId(),
                snapshot.heldItemDisplayName(),
                snapshot.equippedItemIds(),
                target.entityId(),
                target.entityTypeId(),
                target.displayName(),
                target.distance(),
                target.present(),
                snapshot.interactionKeyDown(),
                snapshot.sneaking(),
                snapshot.capturedAtMillis()
        );
    }

    /** Whether two pushes describe the same interaction, ignoring when they were captured and how they read. */
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
