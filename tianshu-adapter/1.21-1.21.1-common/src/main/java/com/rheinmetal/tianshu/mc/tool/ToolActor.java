package com.rheinmetal.tianshu.mc.tool;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * What a server-side tool acts on: the player's body or an agent body, seen through the same few operations.
 *
 * <p>Only the main 36 inventory slots are addressed here, which is why a player {@code Inventory} and a body
 * {@code SimpleContainer} can stand behind the same interface: slots 0..35 mean the same thing in both.</p>
 */
public interface ToolActor {

    ServerLevel level();

    Vec3 position();

    /**
     * The actor as a living entity.
     *
     * <p>Needed by the reads that are about the body itself rather than its hands: health, hunger, air, fire. Both kinds
     * of actor are living entities — a player is one, and so is every carrier the platform creates — so this is the
     * honest common type, not a widening for convenience.</p>
     *
     * <p>Null only when a body has been detached from the world: an id that no longer resolves to an entity is a body
     * whose state can no longer be read, and the caller says so instead of reporting zeros as if they were health.</p>
     */
    LivingEntity living();

    /** The 36-slot main store; never null for an actor that passed the capability check. */
    Container inventory();

    boolean operator();

    /** Hands an item back to the world when the inventory cannot take it. */
    void drop(ItemStack stack);
}