package com.rheinmetal.tianshu.client.tool;

import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.game.LocateRequest;
import com.rheinmetal.tianshu.api.game.LocateResult;
import com.rheinmetal.tianshu.api.game.WorldLocatePort;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.concurrent.CompletableFuture;

/**
 * Finds the nearest structure or biome of a given id, from where an actor is.
 *
 * <p>The world operation itself. Neither the player-side nor the body-side tool owns it — both only adapt arguments and
 * call in. The actor decides which position is the origin of the search.</p>
 *
 * <p>Exactly one of {@code structure} and {@code biome} is required. The two searches differ in what a negative answer
 * means, and the result carries {@code sampled} so a biome "not found" is not mistaken for "there is none": structures
 * are searched exactly, biomes are sampled on a grid.</p>
 */
final class WorldLocateOps {

    private WorldLocateOps() {
    }

    static CompletableFuture<LocateResult> locate(ActorRef actor, String structure, String biome, Integer radius) {
        LocateRequest request = new LocateRequest(structure, biome, radius == null ? 0 : radius);
        if (!request.singleTarget()) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "world.locate needs exactly one of: a structure id or a biome id"));
        }
        return TianshuRuntimePorts.find(WorldLocatePort.class)
                .map(port -> port.locate(actor, request)
                        .thenCompose(result -> result.searched()
                                ? CompletableFuture.completedFuture(result)
                                : CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                                        result.message().isBlank() ? "the search could not run" : result.message()))))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "locating is only available on the server")));
    }
}