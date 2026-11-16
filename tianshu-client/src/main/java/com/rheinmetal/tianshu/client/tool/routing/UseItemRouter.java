package com.rheinmetal.tianshu.client.tool.routing;

import com.rheinmetal.tianshu.api.entity.ActionResult;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.EntityActors;
import com.rheinmetal.tianshu.api.entity.EntityActions;
import com.rheinmetal.tianshu.api.entity.EntityItemUsePort;
import com.rheinmetal.tianshu.api.entity.ItemUseTarget;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.concurrent.CompletableFuture;

/** Routes item use to the provider that owns the body. */
public final class UseItemRouter implements EntityItemUsePort {

    @Override
    public CompletableFuture<ActionResult> use(ActorRef actor, ItemUseTarget target, int holdTicks) {
        return EntityActors.actions(actor.id())
                .flatMap(EntityActions::itemUse)
                .map(handler -> handler.use(actor, target, holdTicks))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "no item use handler for this body")));
    }
}