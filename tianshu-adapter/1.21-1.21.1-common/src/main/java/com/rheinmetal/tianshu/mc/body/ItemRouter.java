package com.rheinmetal.tianshu.mc.body;

import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.agent.AgentBodies;
import com.rheinmetal.tianshu.api.agent.AgentBodyActions;
import com.rheinmetal.tianshu.api.agent.AgentItemPort;
import com.rheinmetal.tianshu.api.agent.EquipRequest;
import com.rheinmetal.tianshu.api.agent.EquipResult;
import com.rheinmetal.tianshu.api.agent.PickupResult;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.concurrent.CompletableFuture;

/**
 * Routes pickup and equip to the body's provider.
 *
 * <p>Pickup keeps a player fallback because collecting drops around the requesting player worked before bodies
 * existed; equipping is body-only and has none.</p>
 */
public final class ItemRouter implements AgentItemPort {

    private final AgentItemPort players;

    public ItemRouter(AgentItemPort players) {
        this.players = players;
    }

    @Override
    public CompletableFuture<PickupResult> pickup(ActorRef actor, int radius) {
        if (actor.kind() == ActorRef.Kind.PLAYER) {
            return players.pickup(actor, radius);
        }
        return AgentBodies.actions(actor.id())
                .flatMap(AgentBodyActions::items)
                .map(handler -> handler.pickup(actor, radius))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "no item handler for this body")));
    }

    @Override
    public CompletableFuture<EquipResult> equip(ActorRef actor, EquipRequest request) {
        return AgentBodies.actions(actor.id())
                .flatMap(AgentBodyActions::items)
                .map(handler -> handler.equip(actor, request))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "no item handler for this body")));
    }
}