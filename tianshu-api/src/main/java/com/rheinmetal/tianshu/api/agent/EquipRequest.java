package com.rheinmetal.tianshu.api.agent;

/**
 * Moves one inventory slot onto a body slot.
 *
 * <p>{@code inventorySlot = -1} clears the target: a main hand loses its active item, an equipment slot returns its
 * item to the inventory. Nothing is ever silently dropped.</p>
 */
public record EquipRequest(int inventorySlot, BodySlot target) {

    public EquipRequest {
        if (target == null) {
            throw new IllegalArgumentException("target is required");
        }
        if (inventorySlot < -1) {
            throw new IllegalArgumentException("inventorySlot must be -1 or a main-slot index");
        }
    }
}