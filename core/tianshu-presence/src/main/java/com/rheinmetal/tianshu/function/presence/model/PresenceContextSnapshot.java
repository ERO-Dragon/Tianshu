package com.rheinmetal.tianshu.function.presence.model;

import java.util.List;

/**
 * What one {@code PRESENCE.QUERY_CONTEXT} capture saw, addressed by the fact groups the caller asked for.
 *
 * <p>The record is presence's own view of one capture: it is assembled from the platform's grouped game reading
 * ({@code ClientGameContextPort}) for exactly the groups the current request asked for, and the client maps the
 * filled parts into typed facts. It holds values, never live Minecraft objects.</p>
 */
public record PresenceContextSnapshot(
        String playerId,
        String dimensionId,
        /**
         * Display name of {@link #dimensionId} in the player's language, resolved by the platform layer through the
         * game's own translatable registry names. Empty when the platform had no name at hand; consumers then fall back
         * to a readable form of the id.
         */
        String dimensionDisplayName,
        PresenceScreenKind screenKind,
        String heldItemId,
        /** Display name of {@link #heldItemId}; same contract as {@link #dimensionDisplayName}. */
        String heldItemDisplayName,
        List<String> equippedItemIds,
        PresenceTargetSnapshot crosshairTarget,
        boolean interactionKeyDown,
        boolean attackKeyDown,
        boolean sneaking,
        PresencePlayerStatus playerStatus,
        PresenceWorldEnvironment worldEnvironment,
        List<PresenceInventoryItem> inventoryItems,
        PresenceEquipment equipment,
        List<PresencePotionEffect> activeEffects,
        long capturedAtMillis
) {
    public PresenceContextSnapshot {
        playerId = clean(playerId);
        dimensionId = clean(dimensionId);
        dimensionDisplayName = clean(dimensionDisplayName);
        heldItemDisplayName = clean(heldItemDisplayName);
        screenKind = screenKind == null ? PresenceScreenKind.NONE : screenKind;
        heldItemId = clean(heldItemId);
        equippedItemIds = equippedItemIds == null ? List.of() : List.copyOf(equippedItemIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList());
        crosshairTarget = crosshairTarget == null ? PresenceTargetSnapshot.empty() : crosshairTarget;
        playerStatus = playerStatus == null ? PresencePlayerStatus.empty() : playerStatus;
        worldEnvironment = worldEnvironment == null ? PresenceWorldEnvironment.empty() : worldEnvironment;
        inventoryItems = inventoryItems == null ? List.of() : List.copyOf(inventoryItems);
        equipment = equipment == null ? PresenceEquipment.empty() : equipment;
        activeEffects = activeEffects == null ? List.of() : List.copyOf(activeEffects);
        if (capturedAtMillis <= 0L) {
            capturedAtMillis = System.currentTimeMillis();
        }
    }

    public static PresenceContextSnapshot empty() {
        return new PresenceContextSnapshot(
                "",
                "",
                "",
                PresenceScreenKind.NONE,
                "",
                "",
                List.of(),
                PresenceTargetSnapshot.empty(),
                false,
                false,
                false,
                PresencePlayerStatus.empty(),
                PresenceWorldEnvironment.empty(),
                List.of(),
                PresenceEquipment.empty(),
                List.of(),
                System.currentTimeMillis()
        );
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
