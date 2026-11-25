package com.rheinmetal.tianshu.neoforge.integration;

import com.rheinmetal.tianshu.mc.tool.ItemStore;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * A NeoForge {@link IItemHandler} seen as an {@link ItemStore}.
 *
 * <p>{@code IItemHandler} is slot-addressed by contract, which is why this mapping is faithful: slot {@code n} here is
 * slot {@code n} of the machine. The insert and extract calls are delegated rather than reimplemented, so a machine that
 * refuses an item for its own reasons keeps refusing it.</p>
 */
public final class NeoForgeItemStore implements ItemStore {

    private final IItemHandler handler;

    public NeoForgeItemStore(IItemHandler handler) {
        this.handler = handler;
    }

    @Override
    public int size() {
        return handler.getSlots();
    }

    @Override
    public ItemStack stackIn(int slot) {
        return slot < 0 || slot >= size() ? ItemStack.EMPTY : handler.getStackInSlot(slot);
    }

    @Override
    public boolean canPlace(int slot, ItemStack stack) {
        return slot >= 0 && slot < size() && handler.isItemValid(slot, stack);
    }

    @Override
    public int slotLimit(int slot, ItemStack stack) {
        if (!canPlace(slot, stack)) {
            return 0;
        }
        ItemStack existing = stackIn(slot);
        int limit = Math.min(stack.getMaxStackSize(), handler.getSlotLimit(slot));
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
        if (slot < 0 || slot >= size() || stack.isEmpty()) {
            return 0;
        }
        // The handler decides what it takes; the remainder says how much it did not.
        ItemStack remainder = handler.insertItem(slot, stack, false);
        return stack.getCount() - (remainder == null ? 0 : remainder.getCount());
    }

    @Override
    public int extract(int slot, int amount) {
        if (slot < 0 || slot >= size() || amount <= 0) {
            return 0;
        }
        ItemStack taken = handler.extractItem(slot, amount, false);
        return taken == null ? 0 : taken.getCount();
    }

    @Override
    public void changed() {
        // The handler notifies its own block entity on insert and extract; there is nothing to tell it here.
    }
}