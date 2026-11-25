package com.rheinmetal.tianshu.mc.tool;

import net.minecraft.world.item.ItemStack;

/**
 * An item storage as the game-side tools see it: slots, what is in them, and how much they take.
 *
 * <p>This exists because "a place that holds items" is not one class in Minecraft. Vanilla containers implement
 * {@code Container}; modded machines usually expose a loader item handler instead, and a machine that exposes only a
 * handler is invisible to anything that insists on {@code Container}. Reading one and writing the other is how the tools
 * ended up unable to see the inventory of most modern modded machines.</p>
 *
 * <p>The contract is deliberately slot-addressed, because that is what the tool API promises: {@code container.inspect}
 * reports a slot index, and {@code container.transfer} moves by that index, so whatever store sits behind them has to
 * agree on what a slot is. A store that cannot address its slots cannot implement this honestly, and must not pretend
 * to.</p>
 */
public interface ItemStore {

    /** A store with no slots: "this block holds nothing I can address". */
    ItemStore NONE = new ItemStore() {
        @Override
        public int size() {
            return 0;
        }

        @Override
        public ItemStack stackIn(int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean canPlace(int slot, ItemStack stack) {
            return false;
        }

        @Override
        public int slotLimit(int slot, ItemStack stack) {
            return 0;
        }

        @Override
        public int insert(int slot, ItemStack stack) {
            return 0;
        }

        @Override
        public int extract(int slot, int amount) {
            return 0;
        }

        @Override
        public void changed() {
        }
    };

    int size();

    /** The stack in a slot; {@link ItemStack#EMPTY} for an empty slot or an index outside the store. */
    ItemStack stackIn(int slot);

    /** Whether this store would accept the item here at all. */
    boolean canPlace(int slot, ItemStack stack);

    /** The most of this stack that fits in the slot as it is now. */
    int slotLimit(int slot, ItemStack stack);

    /**
     * Puts as much of {@code stack} into the slot as fits, leaving the caller's stack untouched, and reports how much
     * went in. Zero means nothing fit.
     */
    int insert(int slot, ItemStack stack);

    /** Removes up to {@code amount} from the slot and reports how much went. */
    int extract(int slot, int amount);

    /** Tells the store its contents changed, where the store needs telling. */
    void changed();
}