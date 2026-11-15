package com.rheinmetal.tianshu.client.tool.routing;

import com.rheinmetal.tianshu.api.agent.ActionResult;
import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.agent.AgentBodies;
import com.rheinmetal.tianshu.api.agent.AgentBodyActions;
import com.rheinmetal.tianshu.api.agent.AgentEntityUsePort;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.concurrent.CompletableFuture;

/** Routes entity interaction to the provider that owns the body. */
public final class EntityUseRouter implements AgentEntityUsePort {

    @Override
    public CompletableFuture<ActionResult> useOn(ActorRef actor, String entityId) {
        return AgentBodies.actions(actor.id())
                .flatMap(AgentBodyActions::entityUse)
                .map(handler -> handler.useOn(actor, entityId))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "no entity use handler for this body")));
    }
}