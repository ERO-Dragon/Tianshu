package com.rheinmetal.tianshu.api.entity;

import java.util.List;
import java.util.Objects;

/** What a pickup actually collected; an empty list means nothing was within reach, not that it failed. */
public record PickupResult(List<PickedItem> items) {

    public PickupResult {
        Objects.requireNonNull(items, "items");
    }
}