package com.rheinmetal.tianshu.api.entity;

/**
 * What an equipped item is used on: nothing (the air/self path) or a block face.
 *
 * <p>The item itself is not named here: a body uses whatever its active slot holds, exactly like a player's main
 * hand. A caller that wants a specific item equips it first.</p>
 */
public sealed interface ItemUseTarget {

    /** Use the item with no block target: eating, throwing, a bucket source, a projectile. */
    record Air() implements ItemUseTarget {
        public static final Air INSTANCE = new Air();
    }

    /** Use the item on a block face, with the carrier deriving the interaction point. */
    record Block(BlockTarget block, BlockFace face) implements ItemUseTarget {
    }
}