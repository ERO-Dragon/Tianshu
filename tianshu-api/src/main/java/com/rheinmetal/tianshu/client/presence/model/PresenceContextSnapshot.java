package com.rheinmetal.tianshu.client.presence.model;

import java.util.List;
import java.util.Map;

public record PresenceContextSnapshot(
        String playerId,
        String dimensionId,
        PresenceScreenKind screenKind,
        String containerKind,
        String heldItemId,
        List<String> equippedItemIds,
        PresenceTargetSnapshot crosshairTarget,
        boolean interactionKeyDown,
        boolean attackKeyDown,
        boolean sneaking,
        PresenceInputKind recentInputKind,
        PresencePlayerStatus playerStatus,
        PresenceWorldEnvironment worldEnvironment,
        List<PresenceInventoryItem> inventoryItems,
        List<PresencePotionEffect> activeEffects,
        Map<String, String> facts,
        long capturedAtMillis
) {
    public PresenceContextSnapshot {
        playerId = clean(playerId);
        dimensionId = clean(dimensionId);
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
                List.of(),
                Map.of(),
                System.currentTimeMillis()
        );
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

}
