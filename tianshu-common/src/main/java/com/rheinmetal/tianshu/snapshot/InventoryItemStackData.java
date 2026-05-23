package com.rheinmetal.tianshu.snapshot;

import java.util.Objects;

public record InventoryItemStackData(
        String itemId,
        String displayName,
        int count,
        int maxStackSize
) {
    public InventoryItemStackData(String itemId, String displayName, int count) {
        this(itemId, displayName, count, 64);
    }

    public InventoryItemStackData {
        itemId = itemId == null ? "" : itemId.trim();
        displayName = displayName == null ? "" : displayName.trim();
        maxStackSize = maxStackSize <= 0 ? 64 : maxStackSize;
    }

    public boolean empty() {
        return count <= 0 || (itemId.isBlank() && displayName.isBlank());
    }

    public String displayOrId() {
        if (!displayName.isBlank()) {
            return displayName;
        }
        return itemId.isBlank() ? "unknown_item" : itemId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof InventoryItemStackData that)) {
            return false;
        }
        return count == that.count && maxStackSize == that.maxStackSize && Objects.equals(itemId, that.itemId) && Objects.equals(displayName, that.displayName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(itemId, displayName, count, maxStackSize);
    }
}
