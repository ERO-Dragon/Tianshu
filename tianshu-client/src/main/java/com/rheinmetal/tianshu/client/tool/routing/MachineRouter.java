package com.rheinmetal.tianshu.client.tool.routing;

import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.agent.AgentBodies;
import com.rheinmetal.tianshu.api.agent.AgentBodyActions;
import com.rheinmetal.tianshu.api.game.BlockMachinePort;
import com.rheinmetal.tianshu.api.game.MachineOpRequest;
import com.rheinmetal.tianshu.api.game.MachineOpResult;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.concurrent.CompletableFuture;

/** Routes machine operations to the body's provider; a player keeps the direct player path. */
public final class MachineRouter implements BlockMachinePort {

    private final BlockMachinePort players;

    public MachineRouter(BlockMachinePort players) {
        this.players = players;
    }

    @Override
    @Deprecated
    public CompletableFuture<MachineOpResult> operate(String playerId, MachineOpRequest request) {
        return players.operate(playerId, request);
    }

    @Override
    public CompletableFuture<MachineOpResult> operate(ActorRef actor, MachineOpRequest request) {
        if (actor.kind() == ActorRef.Kind.PLAYER) {
            return players.operate(actor.id(), request);
        }
        return AgentBodies.actions(actor.id())
                .flatMap(AgentBodyActions::machine)
                .map(handler -> handler.operate(actor, request))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "no machine handler for this body")));
    }
}