package com.rheinmetal.tianshu.api.game;

import com.rheinmetal.tianshu.api.entity.ActorRef;

import java.util.concurrent.CompletableFuture;

/**
 * Breaks one block as the actor, following that actor's own rules.
 *
 * <p>The counterpart of {@code BlockPlacePort}: placing was available to a player and a body, breaking only existed for
 * bodies through {@code body.mine}, which left a module acting for a player unable to remove a block. This is the atomic
 * verb that was missing on the delete side of the same pair.</p>
 *
 * <p>"Following the actor's rules" is not a detail: a creative player breaks instantly, a survival player leaves the
 * drops it should, and a carrier needs the tool and the time. The implementation routes to the path each kind of actor
 * already has, so there is one behaviour per actor kind rather than a second mining implementation here.</p>
 */
public interface BlockBreakPort {

    CompletableFuture<BlockBreakResult> breakBlock(ActorRef actor, BlockBreakRequest request);
}