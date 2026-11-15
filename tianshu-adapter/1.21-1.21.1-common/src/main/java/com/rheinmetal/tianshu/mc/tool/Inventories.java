package com.rheinmetal.tianshu.mc.tool;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/** Inventory operations shared by the tools that move items in and out of the 36-slot main store. */
final class Inventories {

    private Inventories() {
    }

    /**
     * How many of {@code stack} would fit, without touching the inventory.
     *
     * <p>{@link #add} places whatever fits and reports how much it placed, so a caller that must be able to refuse
     * the whole move has to ask first. Checking {@code add}'s result afterwards leaves the partial move behind —
     * and when the source is an equipment slot that means the same items sit in the inventory and on the body at
     * once, which is a duplication, not a failed equip.</p>
     */
    static int roomFor(Container inventory, ItemStack stack, int size) {
        int remaining = stack.getCount();
        for (int slot = 0; slot < size && remaining > 0; slot++) {
            ItemStack existing = inventory.getItem(slot);
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, stack)) {
                continue;
            }
            remaining -= Math.min(existing.getMaxStackSize() - existing.getCount(), remaining);
        }
        for (int slot = 0; slot < size && remaining > 0; slot++) {
            if (!inventory.getItem(slot).isEmpty()) {
                continue;
            }
            remaining -= Math.min(stack.getMaxStackSize(), remaining);
        }
        return stack.getCount() - remaining;
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