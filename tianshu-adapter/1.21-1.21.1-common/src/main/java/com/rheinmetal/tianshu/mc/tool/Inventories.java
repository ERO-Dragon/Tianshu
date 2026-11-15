package com.rheinmetal.tianshu.mc.tool;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/** Inventory operations shared by the tools that move items in and out of the 36-slot main store. */
final class Inventories {

    private Inventories() {
    }

    /**
     * Merges into existing stacks first, then fills empty slots, and returns how many items fit. The source stack is
     * never mutated; the caller decides what to do with the remainder.
     */
    static int add(Container inventory, ItemStack stack, int size) {
        int remaining = stack.getCount();
        for (int slot = 0; slot < size && remaining > 0; slot++) {
            ItemStack existing = inventory.getItem(slot);
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, stack)) {
                continue;
            }
            int room = Math.min(existing.getMaxStackSize() - existing.getCount(), remaining);
            if (room > 0) {
                existing.grow(room);
                remaining -= room;
            }
        }
        for (int slot = 0; slot < size && remaining > 0; slot++) {
            if (!inventory.getItem(slot).isEmpty()) {
                continue;
            }
            ItemStack placed = stack.copy();
            placed.setCount(Math.min(placed.getMaxStackSize(), remaining));
            inventory.setItem(slot, placed);
            remaining -= placed.getCount();
        }
        inventory.setChanged();
        return stack.getCount() - remaining;
    }
}