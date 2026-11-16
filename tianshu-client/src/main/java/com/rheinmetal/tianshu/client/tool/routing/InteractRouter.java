package com.rheinmetal.tianshu.client.tool.routing;

import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.EntityActors;
import com.rheinmetal.tianshu.api.entity.EntityActions;
import com.rheinmetal.tianshu.api.game.BlockInteractPort;
import com.rheinmetal.tianshu.api.game.BlockInteractRequest;
import com.rheinmetal.tianshu.api.game.BlockInteractResult;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.concurrent.CompletableFuture;

/** Routes block use to the body's provider; a player keeps the direct player path. */
public final class InteractRouter implements BlockInteractPort {

    private final BlockInteractPort players;

    public InteractRouter(BlockInteractPort players) {
        this.players = players;
    }

    @Override
    @Deprecated
    public CompletableFuture<BlockInteractResult> interact(String playerId, BlockInteractRequest request) {
        return players.interact(playerId, request);
    }

    @Override
    public CompletableFuture<BlockInteractResult> interact(ActorRef actor, BlockInteractRequest request) {
        if (actor.kind() == ActorRef.Kind.PLAYER) {
            return players.interact(actor.id(), request);
        }
        return EntityActors.actions(actor.id())
                .flatMap(EntityActions::interaction)
                .map(handler -> handler.interact(actor, request))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "no interaction handler for this body")));
    }
}