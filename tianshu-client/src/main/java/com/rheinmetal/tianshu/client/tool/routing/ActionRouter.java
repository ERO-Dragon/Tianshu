package com.rheinmetal.tianshu.client.tool.routing;

import com.rheinmetal.tianshu.api.entity.ActionResult;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.EntityActionPort;
import com.rheinmetal.tianshu.api.entity.EntityActors;
import com.rheinmetal.tianshu.api.entity.EntityActions;
import com.rheinmetal.tianshu.api.entity.BlockTarget;
import com.rheinmetal.tianshu.api.entity.EntityTarget;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.concurrent.CompletableFuture;

/** Routes breaking and striking to the provider that owns the body. */
public final class ActionRouter implements EntityActionPort {

    @Override
    public CompletableFuture<ActionResult> mine(ActorRef actor, BlockTarget target) {
        return EntityActors.actions(actor.id())
                .flatMap(EntityActions::action)
                .map(handler -> handler.mine(actor, target))
                .orElseGet(ActionRouter::noHandler);
    }

    @Override
    public CompletableFuture<ActionResult> attack(ActorRef actor, EntityTarget target) {
        return EntityActors.actions(actor.id())
                .flatMap(EntityActions::action)
                .map(handler -> handler.attack(actor, target))
                .orElseGet(ActionRouter::noHandler);
    }

    private static <T> CompletableFuture<T> noHandler() {
        return CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                "no action handler for this actor"));
    }
}