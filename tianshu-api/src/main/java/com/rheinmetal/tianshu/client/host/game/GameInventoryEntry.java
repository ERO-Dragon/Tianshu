package com.rheinmetal.tianshu.client.host.game;

/** One stack the player is carrying, with the name the platform resolved for it. */
public record GameInventoryEntry(
        String itemId,
        String displayName,
        int count,
        int maxStackSize
) {
    public GameInventoryEntry {
        itemId = clean(itemId);
        displayName = clean(displayName);
        count = Math.max(0, count);
        maxStackSize = Math.max(1, maxStackSize);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}