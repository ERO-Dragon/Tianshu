package com.rheinmetal.tianshu.api.entity;

/**
 * What a body slot now holds; an empty {@code itemId} with count zero means the slot is empty.
 */
public record EquipResult(EntitySlot target, int inventorySlot, String itemId, int count) {

    public EquipResult {
        if (target == null) {
            throw new IllegalArgumentException("target is required");
        }
        if (itemId == null) {
            throw new IllegalArgumentException("itemId is required");
        }
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
    }
}