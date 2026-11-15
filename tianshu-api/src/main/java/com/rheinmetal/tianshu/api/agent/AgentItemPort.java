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

    /**
     * Chooses the body's active slot, or clears it with {@code slot=-1}. The slot only changes what later attacks
     * and breaks use: whether the item is "held" visually is a carrier concern.
     */
    CompletableFuture<EquipResult> equip(ActorRef actor, int slot);
}