package com.rheinmetal.tianshu.client.tool;

import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.EntityActors;
import com.rheinmetal.tianshu.api.entity.EntityCapability;
import com.rheinmetal.tianshu.api.game.BlockMachinePort;
import com.rheinmetal.tianshu.api.game.MachineOpRequest;
import com.rheinmetal.tianshu.api.game.MachineOpResult;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Works a machine at a position: put fuel in, put input in, take the result out.
 *
 * <p>The world operation itself. Neither the player-side nor the body-side tool owns it — both only adapt arguments and
 * call in. The actor decides whose inventory the items move out of and into.</p>
 *
 * <p>It requires {@link EntityCapability#INVENTORY}, not {@code USE}: every operation moves items into or out of a
 * container, so an actor that can only use blocks cannot work the machine. The platform decides which machines are
 * understood (for now the furnace family); an unknown block is a refusal with a reason, never a generic "run this
 * block" escape hatch.</p>
 */
final class MachineOps {

    private static final Set<String> OPERATIONS = Set.of("insert_fuel", "insert_input", "take_output");

    private MachineOps() {
    }

    static CompletableFuture<MachineOpResult> operate(ActorRef actor, int x, int y, int z, String operation,
                                                     Integer playerSlot) {
        String name = operation == null ? "" : operation.trim().toLowerCase(Locale.ROOT);
        if (!OPERATIONS.contains(name)) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "block.machine needs one of: insert_fuel, insert_input, take_output"));
        }
        int slot = playerSlot == null ? -1 : playerSlot;
        if (!name.equals("take_output") && (slot < 0 || slot >= 36)) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "block.machine needs a player slot between 0 and 35 for insert operations"));
        }
        MachineOpRequest normalised = new MachineOpRequest(x, y, z, name, name.equals("take_output") ? -1 : slot);
        Optional<ToolException> refusal = EntityActors.checkCapability(actor, EntityCapability.INVENTORY);
        if (refusal.isPresent()) {
            return CompletableFuture.failedFuture(refusal.get());
        }
        return TianshuRuntimePorts.find(BlockMachinePort.class)
                .map(port -> port.operate(actor, normalised)
                        .thenCompose(result -> result.moved() > 0
                                ? CompletableFuture.completedFuture(result)
                                : CompletableFuture.failedFuture(new ToolException(ToolError.FAILED,
                                        result.message().isBlank() ? "machine operation failed" : result.message()))))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "machine access is only available on the server")));
    }
}