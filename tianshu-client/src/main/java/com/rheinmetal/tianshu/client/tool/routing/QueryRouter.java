package com.rheinmetal.tianshu.client.tool.routing;

import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.agent.AgentBodies;
import com.rheinmetal.tianshu.api.agent.AgentBodyActions;
import com.rheinmetal.tianshu.api.agent.AgentWorldQueryPort;
import com.rheinmetal.tianshu.api.agent.ScanRequest;
import com.rheinmetal.tianshu.api.agent.ScanResult;
import com.rheinmetal.tianshu.api.game.InventorySnapshot;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.concurrent.CompletableFuture;

/** Routes scanning and inventory reads; a player keeps the local fallback it had before bodies existed. */
public final class QueryRouter implements AgentWorldQueryPort {

    private final AgentWorldQueryPort players;

    public QueryRouter(AgentWorldQueryPort players) {
        this.players = players;
    }

    @Override
    public CompletableFuture<ScanResult> scan(ActorRef actor, ScanRequest request) {
        if (actor.kind() == ActorRef.Kind.PLAYER) {
            return players.scan(actor, request);
        }
        return AgentBodies.actions(actor.id())
                .flatMap(AgentBodyActions::query)
                .map(handler -> handler.scan(actor, request))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "no query handler for this body")));
    }

    @Override
    public CompletableFuture<InventorySnapshot> inventory(ActorRef actor) {
        if (actor.kind() == ActorRef.Kind.PLAYER) {
            return players.inventory(actor);
        }
        return AgentBodies.actions(actor.id())
                .flatMap(AgentBodyActions::query)
                .map(handler -> handler.inventory(actor))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "no query handler for this body")));
    }
}