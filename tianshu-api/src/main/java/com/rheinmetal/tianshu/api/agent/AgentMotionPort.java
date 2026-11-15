package com.rheinmetal.tianshu.api.agent;

import java.util.concurrent.CompletableFuture;

/**
 * Moving and turning an agent body.
 *
 * <p>Movement modes are a preference, not a promise: a carrier that only teleports refuses {@code WALK}. The port
 * says nothing about legs, gravity or path-finding, because a body may float.</p>
 */
public interface AgentMotionPort {

    CompletableFuture<MoveResult> move(ActorRef actor, ActorPosition target, MoveOptions options);

    CompletableFuture<ActionResult> look(ActorRef actor, ActorPosition target);
}