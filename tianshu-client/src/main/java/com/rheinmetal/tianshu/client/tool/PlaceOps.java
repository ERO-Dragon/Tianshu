package com.rheinmetal.tianshu.client.tool;

import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.BlockFace;
import com.rheinmetal.tianshu.api.entity.EntityActors;
import com.rheinmetal.tianshu.api.entity.EntityCapability;
import com.rheinmetal.tianshu.api.game.BlockPlacePort;
import com.rheinmetal.tianshu.api.game.BlockPlaceRequest;
import com.rheinmetal.tianshu.api.game.BlockPlaceResult;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Places one item from an actor's inventory into the world.
 *
 * <p>The world operation itself. Neither the player-side nor the body-side tool owns it — both only adapt arguments and
 * call in. The actor decides whether rights, reach and orientation follow a player or a body.</p>
 */
final class PlaceOps {

    private PlaceOps() {
    }

    static CompletableFuture<BlockPlaceResult> place(ActorRef actor, String item, int x, int y, int z, String face) {
        if (item == null || item.isBlank()) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "block placement needs an item id"));
        }
        BlockFace target;
        try {
            target = face == null || face.isBlank()
                    ? null
                    : BlockFace.valueOf(face.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknownFace) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "unknown block face: " + face));
        }
        Optional<ToolException> refusal = EntityActors.checkCapability(actor, EntityCapability.PLACE);
        if (refusal.isPresent()) {
            return CompletableFuture.failedFuture(refusal.get());
        }
        BlockPlaceRequest request = new BlockPlaceRequest(x, y, z, item.trim(), target);
        return TianshuRuntimePorts.find(BlockPlacePort.class)
                .map(port -> port.place(actor, request).thenCompose(result ->
                        result.placed()
                                ? CompletableFuture.completedFuture(result)
                                : CompletableFuture.failedFuture(new ToolException(ToolError.FAILED, result.message()))))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "block placement is only available on the server")));
    }
}