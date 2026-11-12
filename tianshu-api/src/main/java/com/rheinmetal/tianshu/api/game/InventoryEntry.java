package com.rheinmetal.tianshu.api.game;

/**
 * One occupied inventory slot.
 *
 * @param slot   the slot index inside the vanilla inventory container
 * @param itemId the item id in that slot
 * @param count  the stack size
 */
public record InventoryEntry(int slot, String itemId, int count) {
}
