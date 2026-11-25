package com.rheinmetal.tianshu.api.game;

import com.rheinmetal.tianshu.api.entity.ActorRef;

import java.util.concurrent.CompletableFuture;

/**
 * The server-side block placement behind {@code minecraft:block.place}.
 *
 * <p>Placing changes the world and consumes an item, so this exists only on the server; the client ships a
 * declaration that routes through the tool channel. Implementations must complete the future on the server
 * thread.</p>
 *
 * <p>Unlike the older ports this one has no player-only bridge: an adapter either implements it for an
 * {@link ActorRef} or does not register it at all.</p>
 */
public interface BlockPlacePort {

    CompletableFuture<BlockPlaceResult> place(ActorRef actor, BlockPlaceRequest request);
}