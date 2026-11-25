package com.rheinmetal.tianshu.api.game;

import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.concurrent.CompletableFuture;

/**
 * The server-side container transfer behind {@code minecraft:container.transfer}.
 *
 * <p>Moving items changes two inventories, so this exists only on the server; the client ships a declaration that
 * routes through the tool channel. Implementations must complete the future on the server thread.</p>
 */
public interface ContainerTransferPort {

    /**
     * @deprecated implement {@link #transfer(ActorRef, ContainerTransferRequest)} instead; this player-only entry
     *     point stays until every adapter branch has migrated and is removed afterwards.
     */
    @Deprecated
    CompletableFuture<ContainerTransferResult> transfer(String playerId, ContainerTransferRequest request);

    /** Actor-aware entry point; a body must override this, a player call falls back to the player method. */
    default CompletableFuture<ContainerTransferResult> transfer(ActorRef actor, ContainerTransferRequest request) {
        if (actor.kind() == ActorRef.Kind.PLAYER) {
            return transfer(actor.id(), request);
        }
        return CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                "container transfer as an agent body is not implemented on this platform"));
    }
}