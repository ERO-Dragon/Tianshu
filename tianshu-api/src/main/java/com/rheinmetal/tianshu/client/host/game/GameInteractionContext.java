package com.rheinmetal.tianshu.client.host.game;

import java.util.List;

/**
 * The per-tick slice: what the player is looking at, holding and doing.
 *
 * <p>This is the only group a module watches continuously, so it stays small — ids and flags, no inventory, no
 * effects, no world sampling. The display names ride along because the platform is the only layer that can turn a
 * registry id into the player's language.</p>
 */
public record GameInteractionContext(
        String dimensionId,
        String dimensionDisplayName,
        GameScreenKind screenKind,
        String heldItemId,
        String heldItemDisplayName,
        List<String> equippedItemIds,
        GameTarget crosshair,
        boolean useKeyDown,
        boolean attackKeyDown,
        boolean sneaking,
        long capturedAtMillis
) {
    public GameInteractionContext {
        dimensionId = clean(dimensionId);
        dimensionDisplayName = clean(dimensionDisplayName);
        screenKind = screenKind == null ? GameScreenKind.NONE : screenKind;
        heldItemId = clean(heldItemId);
        heldItemDisplayName = clean(heldItemDisplayName);
        equippedItemIds = equippedItemIds == null ? List.of() : List.copyOf(equippedItemIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList());
        crosshair = crosshair == null ? GameTarget.EMPTY : crosshair;
        if (capturedAtMillis <= 0L) {
            capturedAtMillis = System.currentTimeMillis();
        }
    }

    public static GameInteractionContext empty() {
        return new GameInteractionContext("", "", GameScreenKind.NONE, "", "", List.of(), GameTarget.EMPTY,
                false, false, false, System.currentTimeMillis());
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}