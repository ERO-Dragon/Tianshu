package com.rheinmetal.tianshu.api.agent;

import java.util.concurrent.CompletableFuture;

/**
 * Interacting with an entity the way a player right-clicks it: feeding, taming, leashing, trading.
 *
 * <p>Uses the item in the active slot when there is one and an empty hand otherwise, so feeding and leash handling
 * both work without a separate "hand" parameter.</p>
 */
public interface AgentEntityUsePort {

    CompletableFuture<ActionResult> useOn(ActorRef actor, String entityId);
}