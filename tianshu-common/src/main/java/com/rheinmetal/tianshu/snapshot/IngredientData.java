package com.rheinmetal.tianshu.snapshot;

import java.util.Collections;
import java.util.Map;

public final class IngredientData {

    public final String itemId;
    public final String displayName;
    public final int count;
    public final Map<String, String> nbtHints;

    public IngredientData(String itemId, String displayName, int count, Map<String, String> nbtHints) {
        this.itemId = itemId;
        this.displayName = displayName;
        this.count = count;
        this.nbtHints = nbtHints != null ? nbtHints : Collections.emptyMap();
    }

    public IngredientData(String itemId, String displayName, int count) {
        this(itemId, displayName, count, null);
    }

    public String getItemId() { return itemId; }
    public String getDisplayName() { return displayName; }
    public int getCount() { return count; }
    public Map<String, String> getNbtHints() { return nbtHints; }
}
