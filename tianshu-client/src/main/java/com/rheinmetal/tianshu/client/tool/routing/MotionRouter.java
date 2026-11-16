package com.rheinmetal.tianshu.client.tool.routing;

import com.rheinmetal.tianshu.api.entity.ActionResult;
import com.rheinmetal.tianshu.api.entity.ActorPosition;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.EntityActors;
import com.rheinmetal.tianshu.api.entity.EntityActions;
import com.rheinmetal.tianshu.api.entity.EntityMotionPort;
import com.rheinmetal.tianshu.api.entity.MoveOptions;
import com.rheinmetal.tianshu.api.entity.MoveResult;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.concurrent.CompletableFuture;

/**
 * Routes motion calls to whichever provider owns the body.
 *
 * <p>The port registered with the platform is this router, not an implementation: the built-in carriers and a
 * third-party carrier are reached the same way, and "no provider owns this body" is an honest {@code UNAVAILABLE}.</p>
 */
public final class MotionRouter implements EntityMotionPort {

    @Override
    public CompletableFuture<MoveResult> move(ActorRef actor, ActorPosition target, MoveOptions options) {
        return EntityActors.actions(actor.id())
                .flatMap(EntityActions::motion)
                .map(handler -> handler.move(actor, target, options))
                .orElseGet(MotionRouter::noHandler);
    }

    @Override
    public CompletableFuture<ActionResult> look(ActorRef actor, ActorPosition target) {
        return EntityActors.actions(actor.id())
                .flatMap(EntityActions::motion)
                .map(handler -> handler.look(actor, target))
                .orElseGet(MotionRouter::noHandler);
    }

    private static <T> CompletableFuture<T> noHandler() {
        return CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                "no motion handler for this actor"));
    }
}