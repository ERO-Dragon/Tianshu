package com.rheinmetal.tianshu.api.game;

import com.rheinmetal.tianshu.api.entity.ActorRef;

import java.util.concurrent.CompletableFuture;

/**
 * Reads an actor's own state: where it is, how healthy it is, how fed it is.
 *
 * <p>Atomic on purpose: it answers "how is this actor doing right now" and nothing else. It does not report what the
 * actor carries ({@code body.inventory}) or what is around it ({@code body.scan}); a caller that needs those asks for
 * them, and a workflow that needs all three is the caller's composition, not a bigger tool here.</p>
 */
public interface ActorStatusPort {

    CompletableFuture<ActorStatus> status(ActorRef actor);
}