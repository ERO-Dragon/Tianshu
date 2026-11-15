package com.rheinmetal.tianshu.api.agent;

import java.util.Objects;

/** One item stack a pickup collected. */
public record PickedItem(String itemId, int count) {

    public PickedItem {
        Objects.requireNonNull(itemId, "itemId");
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive: " + count);
        }
    }
}