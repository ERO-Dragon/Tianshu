package com.rheinmetal.tianshu.client.tool.routing;

import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.agent.AgentBodies;
import com.rheinmetal.tianshu.api.agent.AgentBodyActions;
import com.rheinmetal.tianshu.api.game.ContainerTransferPort;
import com.rheinmetal.tianshu.api.game.ContainerTransferRequest;
import com.rheinmetal.tianshu.api.game.ContainerTransferResult;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.concurrent.CompletableFuture;

/** Routes container transfers to the body's provider; a player keeps the direct player path. */
public final class TransferRouter implements ContainerTransferPort {

    private final ContainerTransferPort players;

    public TransferRouter(ContainerTransferPort players) {
        this.players = players;
    }

    @Override
    @Deprecated
    public CompletableFuture<ContainerTransferResult> transfer(String playerId,
                                                               ContainerTransferRequest request) {
        return players.transfer(playerId, request);
    }

    @Override
    public CompletableFuture<ContainerTransferResult> transfer(ActorRef actor, ContainerTransferRequest request) {
        if (actor.kind() == ActorRef.Kind.PLAYER) {
            return players.transfer(actor.id(), request);
        }
        return AgentBodies.actions(actor.id())
                .flatMap(AgentBodyActions::transfer)
                .map(handler -> handler.transfer(actor, request))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "no transfer handler for this body")));
    }
}