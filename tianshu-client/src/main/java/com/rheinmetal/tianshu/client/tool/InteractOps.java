package com.rheinmetal.tianshu.client.tool;

import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.EntityActors;
import com.rheinmetal.tianshu.api.entity.EntityCapability;
import com.rheinmetal.tianshu.api.game.BlockInteractPort;
import com.rheinmetal.tianshu.api.game.BlockInteractRequest;
import com.rheinmetal.tianshu.api.game.BlockInteractResult;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Operates a harmless block — a button, a lever, a door.
 *
 * <p>The world operation itself. Neither the player-side nor the body-side tool owns it — both only adapt arguments and
 * call in. The actor decides whether rights and reach follow a player or a body.</p>
 */
final class InteractOps {

    private InteractOps() {
    }

    static CompletableFuture<BlockInteractResult> interact(ActorRef actor, int x, int y, int z) {
        Optional<ToolException> refusal = EntityActors.checkCapability(actor, EntityCapability.USE);
        if (refusal.isPresent()) {
            return CompletableFuture.failedFuture(refusal.get());
        }
        BlockInteractRequest request = new BlockInteractRequest(x, y, z);
        return TianshuRuntimePorts.find(BlockInteractPort.class)
                .map(port -> port.interact(actor, request))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "block interaction is only available on the server")));
    }
}