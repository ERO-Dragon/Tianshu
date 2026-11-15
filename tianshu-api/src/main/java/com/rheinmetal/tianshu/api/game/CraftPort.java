package com.rheinmetal.tianshu.api.game;

import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.concurrent.CompletableFuture;

/**
 * The server-side crafting behind {@code tianshu:craft.recipe}.
 *
 * <p>Crafting consumes items, so this exists only on the server; the client ships a declaration that routes through
 * the tool channel. Implementations must complete the future on the server thread.</p>
 */
public interface CraftPort {

    /**
     * @deprecated implement {@link #craft(ActorRef, CraftRequest)} instead; this player-only entry point stays
     *     until every adapter branch has migrated and is removed afterwards.
     */
    @Deprecated
    CompletableFuture<CraftResult> craft(String playerId, CraftRequest request);

    /** Actor-aware entry point; a body must override this, a player call falls back to the player method. */
    default CompletableFuture<CraftResult> craft(ActorRef actor, CraftRequest request) {
        if (actor.kind() == ActorRef.Kind.PLAYER) {
            return craft(actor.id(), request);
        }
        return CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                "crafting as an agent body is not implemented on this platform"));
    }
}