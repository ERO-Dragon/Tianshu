package com.rheinmetal.tianshu.mc.body;

import com.rheinmetal.tianshu.api.agent.ActionResult;
import com.rheinmetal.tianshu.api.agent.ActorPosition;
import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.agent.AgentBodies;
import com.rheinmetal.tianshu.api.agent.AgentBodyActions;
import com.rheinmetal.tianshu.api.agent.AgentMotionPort;
import com.rheinmetal.tianshu.api.agent.MoveOptions;
import com.rheinmetal.tianshu.api.agent.MoveResult;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.concurrent.CompletableFuture;

/**
 * Routes motion calls to whichever provider owns the body.
 *
 * <p>The port registered with the platform is this router, not an implementation: the built-in carriers and a
 * third-party carrier are reached the same way, and "no provider owns this body" is an honest {@code UNAVAILABLE}.</p>
 */
public final class MotionRouter implements AgentMotionPort {

    @Override
    public CompletableFuture<MoveResult> move(ActorRef actor, ActorPosition target, MoveOptions options) {
        return AgentBodies.actions(actor.id())
                .flatMap(AgentBodyActions::motion)
                .map(handler -> handler.move(actor, target, options))
                .orElseGet(MotionRouter::noHandler);
    }

    @Override
    public CompletableFuture<ActionResult> look(ActorRef actor, ActorPosition target) {
        return AgentBodies.actions(actor.id())
                .flatMap(AgentBodyActions::motion)
                .map(handler -> handler.look(actor, target))
                .orElseGet(MotionRouter::noHandler);
    }

    private static <T> CompletableFuture<T> noHandler() {
        return CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                "no motion handler for this actor"));
    }
}