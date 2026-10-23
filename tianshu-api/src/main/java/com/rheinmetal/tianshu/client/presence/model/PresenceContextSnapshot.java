package com.rheinmetal.tianshu.client.presence.model;

import java.util.List;
import java.util.Map;

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
        String containerKind,
        String heldItemId,
        /** Display name of {@link #heldItemId}; same contract as {@link #dimensionDisplayName}. */
        String heldItemDisplayName,
        List<String> equippedItemIds,
        PresenceTargetSnapshot crosshairTarget,
        boolean interactionKeyDown,
        boolean attackKeyDown,
        boolean sneaking,
        PresenceInputKind recentInputKind,
        PresencePlayerStatus playerStatus,
        PresenceWorldEnvironment worldEnvironment,
        List<PresenceInventoryItem> inventoryItems,
        PresenceEquipment equipment,
        List<PresencePotionEffect> activeEffects,
        Map<String, String> facts,
        long capturedAtMillis
) {
    /** Snapshot without display names: consumers fall back to a readable form of each id. */
    public PresenceContextSnapshot(String playerId, String dimensionId, PresenceScreenKind screenKind, String containerKind, String heldItemId, List<String> equippedItemIds, PresenceTargetSnapshot crosshairTarget, boolean interactionKeyDown, boolean attackKeyDown, boolean sneaking, PresenceInputKind recentInputKind, PresencePlayerStatus playerStatus, PresenceWorldEnvironment worldEnvironment, List<PresenceInventoryItem> inventoryItems, PresenceEquipment equipment, List<PresencePotionEffect> activeEffects, Map<String, String> facts, long capturedAtMillis) {
        this(playerId, dimensionId, "", screenKind, containerKind, heldItemId, "", equippedItemIds, crosshairTarget, interactionKeyDown, attackKeyDown, sneaking, recentInputKind, playerStatus, worldEnvironment, inventoryItems, equipment, activeEffects, facts, capturedAtMillis);
    }

    public PresenceContextSnapshot {
        playerId = clean(playerId);
        dimensionId = clean(dimensionId);
        dimensionDisplayName = clean(dimensionDisplayName);
        heldItemDisplayName = clean(heldItemDisplayName);
        screenKind = screenKind == null ? PresenceScreenKind.NONE : screenKind;
        containerKind = clean(containerKind);
        heldItemId = clean(heldItemId);
        equippedItemIds = equippedItemIds == null ? List.of() : List.copyOf(equippedItemIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList());
        crosshairTarget = crosshairTarget == null ? PresenceTargetSnapshot.empty() : crosshairTarget;
        recentInputKind = recentInputKind == null ? PresenceInputKind.NONE : recentInputKind;
        playerStatus = playerStatus == null ? PresencePlayerStatus.empty() : playerStatus;
        worldEnvironment = worldEnvironment == null ? PresenceWorldEnvironment.empty() : worldEnvironment;
        inventoryItems = inventoryItems == null ? List.of() : List.copyOf(inventoryItems);
        equipment = equipment == null ? PresenceEquipment.empty() : equipment;
        activeEffects = activeEffects == null ? List.of() : List.copyOf(activeEffects);
        facts = facts == null ? Map.of() : Map.copyOf(facts);
        if (capturedAtMillis <= 0L) {
            capturedAtMillis = System.currentTimeMillis();
        }
    }

    public static PresenceContextSnapshot empty() {
        return new PresenceContextSnapshot(
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
                PresenceInputKind.NONE,
                PresencePlayerStatus.empty(),
                PresenceWorldEnvironment.empty(),
                List.of(),
                PresenceEquipment.empty(),
                List.of(),
                Map.of(),
                System.currentTimeMillis()
        );
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

}
