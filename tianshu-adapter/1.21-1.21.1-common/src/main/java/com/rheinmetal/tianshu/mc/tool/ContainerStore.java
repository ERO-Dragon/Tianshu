package com.rheinmetal.tianshu.mc.tool;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * A vanilla {@link Container} seen as an {@link ItemStore}.
 *
 * <p>This is the fallback, not the only case: it covers vanilla blocks and every modded one that chose the vanilla
 * contract, which is what the tools used to be limited to.</p>
 */
public final class ContainerStore implements ItemStore {

    private final Container container;

    public ContainerStore(Container container) {
        this.container = container;
    }

    @Override
    public int size() {
        return container.getContainerSize();
    }

    @Override
    public ItemStack stackIn(int slot) {
        return slot < 0 || slot >= size() ? ItemStack.EMPTY : container.getItem(slot);
    }

    @Override
    public boolean canPlace(int slot, ItemStack stack) {
        return slot >= 0 && slot < size() && container.canPlaceItem(slot, stack);
    }

    @Override
    public int slotLimit(int slot, ItemStack stack) {
        if (!canPlace(slot, stack)) {
            return 0;
        }
        ItemStack existing = container.getItem(slot);
        int limit = Math.min(stack.getMaxStackSize(), container.getMaxStackSize());
        if (existing.isEmpty()) {
            return limit;
        }
        if (!ItemStack.isSameItemSameComponents(existing, stack)) {
            return 0;
        }
        return Math.max(0, limit - existing.getCount());
    }

    @Override
    public int insert(int slot, ItemStack stack) {
        int moved = Math.min(stack.getCount(), slotLimit(slot, stack));
        if (moved <= 0) {
            return 0;
        }
        ItemStack existing = container.getItem(slot);
        if (existing.isEmpty()) {
            ItemStack placed = stack.copy();
            placed.setCount(moved);
            container.setItem(slot, placed);
        } else {
            existing.grow(moved);
            container.setItem(slot, existing);
        }
        return moved;
    }

    @Override
    public int extract(int slot, int amount) {
        ItemStack existing = stackIn(slot);
        if (existing.isEmpty() || amount <= 0) {
            return 0;
        }
        int moved = Math.min(amount, existing.getCount());
        existing.shrink(moved);
        if (existing.isEmpty()) {
            container.setItem(slot, ItemStack.EMPTY);
        } else {
            container.setItem(slot, existing);
        }
        return moved;
    }

    @Override
    public void changed() {
        container.setChanged();
    }
}