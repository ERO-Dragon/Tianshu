package com.rheinmetal.tianshu.snapshot;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class IngredientData {

    public final String itemId;
    public final String displayName;
    public final int count;
    public final Map<String, String> nbtHints;
    public final String tagId;
    public final List<String> tagItems;

    public IngredientData(String itemId, String displayName, int count, Map<String, String> nbtHints,
                          String tagId, List<String> tagItems) {
        this.itemId = itemId;
        this.displayName = displayName;
        this.count = count;
        this.nbtHints = nbtHints != null ? nbtHints : Collections.emptyMap();
        this.tagId = tagId;
        this.tagItems = tagItems != null ? Collections.unmodifiableList(tagItems) : Collections.emptyList();
    }

    public IngredientData(String itemId, String displayName, int count, Map<String, String> nbtHints) {
        this(itemId, displayName, count, nbtHints, null, null);
    }

    public IngredientData(String itemId, String displayName, int count) {
        this(itemId, displayName, count, null, null, null);
    }

    public String getItemId() { return itemId; }
    public String getDisplayName() { return displayName; }
    public int getCount() { return count; }
    public Map<String, String> getNbtHints() { return nbtHints; }
    public String getTagId() { return tagId; }
    public List<String> getTagItems() { return tagItems; }
}
