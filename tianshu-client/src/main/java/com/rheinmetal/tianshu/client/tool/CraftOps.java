package com.rheinmetal.tianshu.client.tool;

import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.EntityActors;
import com.rheinmetal.tianshu.api.entity.EntityCapability;
import com.rheinmetal.tianshu.api.game.CraftPort;
import com.rheinmetal.tianshu.api.game.CraftRequest;
import com.rheinmetal.tianshu.api.game.CraftResult;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Crafts one named recipe once.
 *
 * <p>The world operation itself. Neither the player-side nor the body-side tool owns it — both only adapt arguments and
 * call in. The actor decides whose ingredients are consumed.</p>
 *
 * <p>It only ever crafts the recipe it was given — choosing the recipe is the caller's job, so a wrong assumption
 * becomes a visible refusal instead of a surprise item.</p>
 */
final class CraftOps {

    private CraftOps() {
    }

    static CompletableFuture<CraftResult> craft(ActorRef actor, String recipeId) {
        if (recipeId == null || recipeId.isBlank()) {
            return CompletableFuture.failedFuture(
                    new ToolException(ToolError.INVALID_ARGS, "craft.recipe needs a recipe id"));
        }
        Optional<ToolException> refusal = EntityActors.checkCapability(actor, EntityCapability.INVENTORY);
        if (refusal.isPresent()) {
            return CompletableFuture.failedFuture(refusal.get());
        }
        return TianshuRuntimePorts.find(CraftPort.class)
                .map(port -> port.craft(actor, new CraftRequest(recipeId.trim()))
                        .thenCompose(result -> result.crafted()
                                ? CompletableFuture.completedFuture(result)
                                : CompletableFuture.failedFuture(new ToolException(ToolError.FAILED,
                                        result.message().isBlank() ? "crafting failed" : result.message()))))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "crafting is only available on the server")));
    }
}