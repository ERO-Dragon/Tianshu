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

    public PresenceContextSnapshot withRealtimeFieldsFrom(PresenceContextSnapshot realtime) {
        if (realtime == null) {
            return this;
        }
        return new PresenceContextSnapshot(
                realtime.playerId(),
                realtime.dimensionId(),
                realtime.screenKind(),
                realtime.containerKind(),
                realtime.heldItemId(),
                realtime.equippedItemIds(),
                realtime.crosshairTarget(),
                realtime.interactionKeyDown(),
                realtime.attackKeyDown(),
                realtime.sneaking(),
                realtime.recentInputKind(),
                playerStatus,
                worldEnvironment,
                inventoryItems,
                activeEffects,
                mergeFacts(facts, realtime.facts()),
                Math.max(capturedAtMillis, realtime.capturedAtMillis())
        );
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static Map<String, String> mergeFacts(Map<String, String> base, Map<String, String> realtime) {
        if ((base == null || base.isEmpty()) && (realtime == null || realtime.isEmpty())) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, String> result = new java.util.LinkedHashMap<>();
        if (base != null) {
            result.putAll(base);
        }
        if (realtime != null) {
            result.putAll(realtime);
        }
        return Map.copyOf(result);
    }
}
