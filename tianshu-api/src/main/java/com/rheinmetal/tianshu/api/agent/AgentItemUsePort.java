package com.rheinmetal.tianshu.api.agent;

import java.util.concurrent.CompletableFuture;

/**
 * Using the item in the body's active slot, the way a player right-clicks.
 *
 * <p>Only instant uses are promised: a bucket, flint and steel, bone meal, a spawn egg, a thrown projectile. Items
 * that need a held use (food, a bow, a shield) are refused with an explicit message until a sustained-use session
 * exists; silently returning success without consuming anything would be a lie.</p>
 */
public interface AgentItemUsePort {

    CompletableFuture<ActionResult> use(ActorRef actor, ItemUseTarget target);
}