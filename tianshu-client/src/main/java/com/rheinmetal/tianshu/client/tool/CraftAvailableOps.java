package com.rheinmetal.tianshu.client.tool;

import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.game.CraftableRecipeList;
import com.rheinmetal.tianshu.api.game.RecipeAvailabilityPort;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.concurrent.CompletableFuture;

/**
 * What an actor could craft right now, from what it is carrying.
 *
 * <p>The world operation itself. Neither the player-side nor the body-side tool owns it — both only adapt arguments and
 * call in. The actor decides whose inventory is read.</p>
 */
final class CraftAvailableOps {

    private CraftAvailableOps() {
    }

    static CompletableFuture<CraftableRecipeList> available(ActorRef actor, Integer limit) {
        int requested = limit == null ? 0 : limit;
        return TianshuRuntimePorts.find(RecipeAvailabilityPort.class)
                .map(port -> port.available(actor, requested))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "recipe availability is only available on the server")));
    }
}