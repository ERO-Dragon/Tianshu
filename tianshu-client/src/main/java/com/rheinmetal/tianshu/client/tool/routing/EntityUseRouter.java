package com.rheinmetal.tianshu.client.tool.routing;

import com.rheinmetal.tianshu.api.entity.ActionResult;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.EntityActors;
import com.rheinmetal.tianshu.api.entity.EntityActions;
import com.rheinmetal.tianshu.api.entity.EntityUsePort;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.concurrent.CompletableFuture;

/** Routes entity interaction to the provider that owns the body. */
public final class EntityUseRouter implements EntityUsePort {

    @Override
    public CompletableFuture<ActionResult> useOn(ActorRef actor, String entityId) {
        return EntityActors.actions(actor.id())
                .flatMap(EntityActions::entityUse)
                .map(handler -> handler.useOn(actor, entityId))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "no entity use handler for this body")));
    }
}