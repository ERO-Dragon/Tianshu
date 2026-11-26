package com.rheinmetal.tianshu.client.tool;

import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.game.BlockInspectPort;
import com.rheinmetal.tianshu.api.game.BlockInspectRequest;
import com.rheinmetal.tianshu.api.game.BlockInspectResult;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.concurrent.CompletableFuture;

/**
 * Reads one block: what it is, its state, and every storage it exposes.
 *
 * <p>The world operation itself. Neither the player-side nor the body-side tool owns it — both only adapt arguments and
 * call in. The actor decides whether the block is within reach and who reads it.</p>
 */
final class BlockInspectOps {

    private BlockInspectOps() {
    }

    static CompletableFuture<BlockInspectResult> inspect(ActorRef actor, int x, int y, int z) {
        BlockInspectRequest request = new BlockInspectRequest(x, y, z);
        return TianshuRuntimePorts.find(BlockInspectPort.class)
                .map(port -> port.inspect(actor, request)
                        .thenCompose(result -> result.inspected()
                                ? CompletableFuture.completedFuture(result)
                                : CompletableFuture.failedFuture(new ToolException(ToolError.FAILED,
                                        result.message().isBlank() ? "nothing could be read there" : result.message()))))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "block inspection is only available on the server")));
    }
}