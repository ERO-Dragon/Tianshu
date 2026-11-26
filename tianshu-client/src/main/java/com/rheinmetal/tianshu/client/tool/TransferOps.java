package com.rheinmetal.tianshu.client.tool;

import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.EntityActors;
import com.rheinmetal.tianshu.api.entity.EntityCapability;
import com.rheinmetal.tianshu.api.game.ContainerTransferPort;
import com.rheinmetal.tianshu.api.game.ContainerTransferRequest;
import com.rheinmetal.tianshu.api.game.ContainerTransferResult;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Moves items between a container and an actor's inventory.
 *
 * <p>The world operation itself. Neither the player-side nor the body-side tool owns it — both only adapt arguments and
 * call in. The actor decides whose inventory the slots belong to.</p>
 *
 * <p>One slot pair per call. It refuses negative slots and counts outside 1..64 before anything reaches the game, so a
 * malformed call never becomes a partial move.</p>
 */
final class TransferOps {

    private TransferOps() {
    }

    static CompletableFuture<ContainerTransferResult> transfer(ActorRef actor, int x, int y, int z, int containerSlot,
                                                               int playerSlot, int count, boolean toPlayer) {
        if (containerSlot < 0 || playerSlot < 0 || playerSlot >= 36 || count < 1 || count > 64) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "container.transfer needs non-negative slots and a count between 1 and 64"));
        }
        Optional<ToolException> refusal = EntityActors.checkCapability(actor, EntityCapability.INVENTORY);
        if (refusal.isPresent()) {
            return CompletableFuture.failedFuture(refusal.get());
        }
        ContainerTransferRequest request = new ContainerTransferRequest(x, y, z, containerSlot, playerSlot, count,
                toPlayer);
        return TianshuRuntimePorts.find(ContainerTransferPort.class)
                .map(port -> port.transfer(actor, request))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "container access is only available on the server")));
    }
}