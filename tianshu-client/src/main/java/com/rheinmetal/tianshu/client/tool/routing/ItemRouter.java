package com.rheinmetal.tianshu.client.tool.routing;

import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.EntityActors;
import com.rheinmetal.tianshu.api.entity.EntityActions;
import com.rheinmetal.tianshu.api.entity.EntityItemPort;
import com.rheinmetal.tianshu.api.entity.DropResult;
import com.rheinmetal.tianshu.api.entity.EquipRequest;
import com.rheinmetal.tianshu.api.entity.EquipResult;
import com.rheinmetal.tianshu.api.entity.PickupResult;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.concurrent.CompletableFuture;

/**
 * Routes pickup and equip to the body's provider.
 *
 * <p>Pickup keeps a player fallback because collecting drops around the requesting player worked before bodies
 * existed; equipping is body-only and has none.</p>
 */
public final class ItemRouter implements EntityItemPort {

    private final EntityItemPort players;

    public ItemRouter(EntityItemPort players) {
        this.players = players;
    }

    @Override
    public CompletableFuture<PickupResult> pickup(ActorRef actor, int radius) {
        if (actor.kind() == ActorRef.Kind.PLAYER) {
            return players.pickup(actor, radius);
        }
        return EntityActors.actions(actor.id())
                .flatMap(EntityActions::items)
                .map(handler -> handler.pickup(actor, radius))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "no item handler for this body")));
    }

    @Override
    public CompletableFuture<DropResult> drop(ActorRef actor, int slot, int count) {
        return EntityActors.actions(actor.id())
                .flatMap(EntityActions::items)
                .map(handler -> handler.drop(actor, slot, count))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "no item handler for this body")));
    }

    @Override
    public CompletableFuture<EquipResult> equip(ActorRef actor, EquipRequest request) {
        return EntityActors.actions(actor.id())
                .flatMap(EntityActions::items)
                .map(handler -> handler.equip(actor, request))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "no item handler for this body")));
    }
}