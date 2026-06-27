package com.rheinmetal.tianshu.client.presence;

public record PresenceInventoryItem(
        String itemId,
        String displayName,
        int count,
        int maxStackSize
) {
    public PresenceInventoryItem {
        itemId = clean(itemId);
        displayName = clean(displayName);
        count = Math.max(0, count);
        maxStackSize = maxStackSize <= 0 ? 64 : maxStackSize;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
