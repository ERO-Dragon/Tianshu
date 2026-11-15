package com.rheinmetal.tianshu.client.tool.routing;

import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.agent.AgentBodies;
import com.rheinmetal.tianshu.api.agent.AgentBodyActions;
import com.rheinmetal.tianshu.api.game.CraftPort;
import com.rheinmetal.tianshu.api.game.CraftRequest;
import com.rheinmetal.tianshu.api.game.CraftResult;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.concurrent.CompletableFuture;

/** Routes crafting to the body's provider; a player keeps the direct player path. */
public final class CraftRouter implements CraftPort {

    private final CraftPort players;

    public CraftRouter(CraftPort players) {
        this.players = players;
    }

    @Override
    @Deprecated
    public CompletableFuture<CraftResult> craft(String playerId, CraftRequest request) {
        return players.craft(playerId, request);
    }

    @Override
    public CompletableFuture<CraftResult> craft(ActorRef actor, CraftRequest request) {
        if (actor.kind() == ActorRef.Kind.PLAYER) {
            return players.craft(actor.id(), request);
        }
        return AgentBodies.actions(actor.id())
                .flatMap(AgentBodyActions::craft)
                .map(handler -> handler.craft(actor, request))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "no craft handler for this body")));
    }
}