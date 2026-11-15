package com.rheinmetal.tianshu.mc.body;

import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.agent.AgentBodies;
import com.rheinmetal.tianshu.api.agent.AgentBodyActions;
import com.rheinmetal.tianshu.api.game.BlockPlacePort;
import com.rheinmetal.tianshu.api.game.BlockPlaceRequest;
import com.rheinmetal.tianshu.api.game.BlockPlaceResult;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.concurrent.CompletableFuture;

/** Routes placement to the body's provider; a player keeps the direct player path it had before bodies existed. */
public final class PlaceRouter implements BlockPlacePort {

    private final BlockPlacePort players;

    public PlaceRouter(BlockPlacePort players) {
        this.players = players;
    }

    @Override
    public CompletableFuture<BlockPlaceResult> place(ActorRef actor, BlockPlaceRequest request) {
        if (actor.kind() == ActorRef.Kind.PLAYER) {
            return players.place(actor, request);
        }
        return AgentBodies.actions(actor.id())
                .flatMap(AgentBodyActions::placement)
                .map(handler -> handler.place(actor, request))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "no placement handler for this body")));
    }
}