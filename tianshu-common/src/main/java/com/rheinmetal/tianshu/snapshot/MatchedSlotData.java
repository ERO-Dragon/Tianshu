package com.rheinmetal.tianshu.snapshot;

import java.util.Objects;

public final class MatchedSlotData {

    public final int slotIndex;
    public final String itemId;
    public final String displayName;
    public final int count;

    public MatchedSlotData(int slotIndex, String itemId, String displayName, int count) {
        this.slotIndex = slotIndex;
        this.itemId = itemId;
        this.displayName = displayName;
        this.count = count;
    }

    public int getSlotIndex() { return slotIndex; }
    public String getItemId() { return itemId; }
    public String getDisplayName() { return displayName; }
    public int getCount() { return count; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MatchedSlotData that = (MatchedSlotData) o;
        return Objects.equals(itemId, that.itemId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(itemId);
    }
}
