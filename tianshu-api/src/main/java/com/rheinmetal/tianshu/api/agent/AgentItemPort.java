package com.rheinmetal.tianshu.api.agent;

import java.util.concurrent.CompletableFuture;

/**
 * Items the actor handles itself: collecting dropped stacks into its own inventory.
 *
 * <p>Requires the {@code INVENTORY} capability, because there is nowhere to put the result otherwise. A body
 * without an inventory is refused before the call reaches this port.</p>
 */
public interface AgentItemPort {

    CompletableFuture<PickupResult> pickup(ActorRef actor, int radius);

    /** Drops part of one inventory slot at the body's feet; {@code count <= 0} means the whole stack. */
    CompletableFuture<DropResult> drop(ActorRef actor, int slot, int count);

    /**
     * Moves an inventory slot onto a body slot, or clears it with {@code inventorySlot=-1}. The main hand only
     * changes what later attacks and breaks use; the other slots are applied to the carrier, so armour counts.
     */
    CompletableFuture<EquipResult> equip(ActorRef actor, EquipRequest request);
}