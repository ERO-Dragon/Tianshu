package com.rheinmetal.tianshu.api.agent;

import java.util.concurrent.CompletableFuture;

/**
 * Actions that change the world through a body: breaking a block and striking an entity.
 *
 * <p>There is no hand and no slot in this contract: whether a tool is used, and whether it wears out, belongs to
 * the carrier. A body that cannot do one of these does not declare the capability, so the refusal happens before
 * the call reaches a port.</p>
 */
public interface AgentActionPort {

    CompletableFuture<ActionResult> mine(ActorRef actor, BlockTarget target);

    CompletableFuture<ActionResult> attack(ActorRef actor, EntityTarget target);
}