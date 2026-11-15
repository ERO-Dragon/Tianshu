package com.rheinmetal.tianshu.mc.tool;

import net.minecraft.server.level.ServerLevel;
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

    /** The 36-slot main store; never null for an actor that passed the capability check. */
    Container inventory();

    boolean operator();

    /** Hands an item back to the world when the inventory cannot take it. */
    void drop(ItemStack stack);
}