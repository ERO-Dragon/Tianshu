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
}