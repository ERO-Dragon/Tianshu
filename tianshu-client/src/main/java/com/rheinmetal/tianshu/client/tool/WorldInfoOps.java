package com.rheinmetal.tianshu.client.tool;

import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.game.WorldInfo;
import com.rheinmetal.tianshu.api.game.WorldInfoPort;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.concurrent.CompletableFuture;

/**
 * The world as it is around an actor: dimension, biome, time, weather, difficulty, light.
 *
 * <p>The world operation itself. Neither the player-side nor the body-side tool owns it — both only adapt arguments and
 * call in. The actor decides which position the world is read at.</p>
 */
final class WorldInfoOps {

    private WorldInfoOps() {
    }

    static CompletableFuture<WorldInfo> info(ActorRef actor) {
        return TianshuRuntimePorts.find(WorldInfoPort.class)
                .map(port -> port.info(actor)
                        .thenCompose(info -> info.read()
                                ? CompletableFuture.completedFuture(info)
                                : CompletableFuture.failedFuture(new ToolException(ToolError.FAILED,
                                        info.message().isBlank() ? "the world could not be read" : info.message()))))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "world information is only available on the server")));
    }
}