package com.rheinmetal.tianshu.api.game;

import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.concurrent.CompletableFuture;

/**
 * The server-side block interaction behind {@code tianshu:block.interact}.
 *
 * <p>Interaction is a world change, so this exists only on the server; the client ships a declaration that routes
 * through the tool channel. Implementations must complete the future on the server thread.</p>
 */
public interface BlockInteractPort {

    /**
     * @deprecated implement {@link #interact(ActorRef, BlockInteractRequest)} instead; this player-only entry point
     *     stays until every adapter branch has migrated and is removed afterwards.
     */
    @Deprecated
    CompletableFuture<BlockInteractResult> interact(String playerId, BlockInteractRequest request);

    /** Actor-aware entry point; a body must override this, a player call falls back to the player method. */
    default CompletableFuture<BlockInteractResult> interact(ActorRef actor, BlockInteractRequest request) {
        if (actor.kind() == ActorRef.Kind.PLAYER) {
            return interact(actor.id(), request);
        }
        return CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                "block interaction as an agent body is not implemented on this platform"));
    }
}