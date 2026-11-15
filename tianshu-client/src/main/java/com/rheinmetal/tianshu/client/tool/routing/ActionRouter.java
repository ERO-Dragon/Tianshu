package com.rheinmetal.tianshu.client.tool.routing;

import com.rheinmetal.tianshu.api.agent.ActionResult;
import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.agent.AgentActionPort;
import com.rheinmetal.tianshu.api.agent.AgentBodies;
import com.rheinmetal.tianshu.api.agent.AgentBodyActions;
import com.rheinmetal.tianshu.api.agent.BlockTarget;
import com.rheinmetal.tianshu.api.agent.EntityTarget;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.concurrent.CompletableFuture;

/** Routes breaking and striking to the provider that owns the body. */
public final class ActionRouter implements AgentActionPort {

    @Override
    public CompletableFuture<ActionResult> mine(ActorRef actor, BlockTarget target) {
        return AgentBodies.actions(actor.id())
                .flatMap(AgentBodyActions::action)
                .map(handler -> handler.mine(actor, target))
                .orElseGet(ActionRouter::noHandler);
    }

    @Override
    public CompletableFuture<ActionResult> attack(ActorRef actor, EntityTarget target) {
        return AgentBodies.actions(actor.id())
                .flatMap(AgentBodyActions::action)
                .map(handler -> handler.attack(actor, target))
                .orElseGet(ActionRouter::noHandler);
    }

    private static <T> CompletableFuture<T> noHandler() {
        return CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                "no action handler for this actor"));
    }
}