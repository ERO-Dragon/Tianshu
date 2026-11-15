package com.rheinmetal.tianshu.mc.body;

import com.rheinmetal.tianshu.api.agent.ActionResult;
import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.agent.AgentBodies;
import com.rheinmetal.tianshu.api.agent.AgentBodyActions;
import com.rheinmetal.tianshu.api.agent.AgentItemUsePort;
import com.rheinmetal.tianshu.api.agent.ItemUseTarget;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.concurrent.CompletableFuture;

/** Routes item use to the provider that owns the body. */
public final class UseItemRouter implements AgentItemUsePort {

    @Override
    public CompletableFuture<ActionResult> use(ActorRef actor, ItemUseTarget target) {
        return AgentBodies.actions(actor.id())
                .flatMap(AgentBodyActions::itemUse)
                .map(handler -> handler.use(actor, target))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "no item use handler for this body")));
    }
}