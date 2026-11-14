package com.rheinmetal.tianshu.client.host.game;

/** One stack inside the open container, with the slot index the menu gave it. */
public record GameContainerEntry(
        int slot,
        String itemId,
        String displayName,
        int count
) {
    public GameContainerEntry {
        slot = Math.max(0, slot);
        itemId = clean(itemId);
        displayName = clean(displayName);
        count = Math.max(0, count);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
