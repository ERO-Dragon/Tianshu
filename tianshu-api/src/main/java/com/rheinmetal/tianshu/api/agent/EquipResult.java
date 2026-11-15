package com.rheinmetal.tianshu.api.agent;

import java.util.Objects;

/** What a body holds in its active slot; {@code slot=-1} and an empty id mean "nothing equipped". */
public record EquipResult(int slot, String itemId, int count) {

    public EquipResult {
        Objects.requireNonNull(itemId, "itemId");
        if (slot < -1) {
            throw new IllegalArgumentException("slot must be -1 or a main-slot index: " + slot);
        }
    }
}