package com.rheinmetal.tianshu.client.tool;

import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.game.ItemInspectPort;
import com.rheinmetal.tianshu.api.game.ItemInspectRequest;
import com.rheinmetal.tianshu.api.game.ItemInspectResult;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.concurrent.CompletableFuture;

/**
 * Reads one stack in an actor's inventory: durability, components, custom name.
 *
 * <p>The world operation itself. Neither the player-side nor the body-side tool owns it — both only adapt arguments and
 * call in. The actor decides whose inventory slot is read.</p>
 */
final class ItemInspectOps {

    private ItemInspectOps() {
    }

    static CompletableFuture<ItemInspectResult> inspect(ActorRef actor, int slot) {
        ItemInspectRequest request = new ItemInspectRequest(slot);
        return TianshuRuntimePorts.find(ItemInspectPort.class)
                .map(port -> port.inspect(actor, request)
                        .thenCompose(result -> result.read()
                                ? CompletableFuture.completedFuture(result)
                                : CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                                        result.message().isBlank() ? "the slot could not be read" : result.message()))))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "item inspection is only available on the server")));
    }
}