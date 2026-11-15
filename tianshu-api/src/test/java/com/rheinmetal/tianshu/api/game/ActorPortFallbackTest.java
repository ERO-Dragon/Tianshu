package com.rheinmetal.tianshu.api.game;

import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The actor-aware overloads on the game ports are the migration bridge: a player call must still reach the
 * player-only method an adapter implements today, and a body call must fail explicitly instead of guessing.
 */
class ActorPortFallbackTest {

    private final AtomicReference<String> legacyPlayerId = new AtomicReference<>();

    @Test
    void blockInteractionFallsBackForAPlayerAndRefusesABody() {
        BlockInteractPort port = new BlockInteractPort() {
            @Override
            public CompletableFuture<BlockInteractResult> interact(String playerId,
                                                                   BlockInteractRequest request) {
                legacyPlayerId.set(playerId);
                return CompletableFuture.completedFuture(
                        new BlockInteractResult("minecraft:oak_door", true, "operated"));
            }
        };

        port.interact(ActorRef.player("p", true), new BlockInteractRequest(0, 0, 0)).join();
        assertEquals("p", legacyPlayerId.get());
        assertRefusedAsUnavailable(
                port.interact(ActorRef.body("b", true), new BlockInteractRequest(0, 0, 0)));
    }

    @Test
    void containerTransferFallsBackForAPlayerAndRefusesABody() {
        ContainerTransferPort port = new ContainerTransferPort() {
            @Override
            public CompletableFuture<ContainerTransferResult> transfer(String playerId,
                                                                       ContainerTransferRequest request) {
                legacyPlayerId.set(playerId);
                return CompletableFuture.completedFuture(
                        new ContainerTransferResult("minecraft:iron_ingot", 1, "moved"));
            }
        };

        port.transfer(ActorRef.player("p", true),
                new ContainerTransferRequest(0, 0, 0, 0, 0, 1, true)).join();
        assertEquals("p", legacyPlayerId.get());
        assertRefusedAsUnavailable(
                port.transfer(ActorRef.body("b", true), new ContainerTransferRequest(0, 0, 0, 0, 0, 1, true)));
    }

    @Test
    void craftingFallsBackForAPlayerAndRefusesABody() {
        CraftPort port = new CraftPort() {
            @Override
            public CompletableFuture<CraftResult> craft(String playerId, CraftRequest request) {
                legacyPlayerId.set(playerId);
                return CompletableFuture.completedFuture(new CraftResult("minecraft:stick", 4, true, "crafted"));
            }
        };

        port.craft(ActorRef.player("p", true), new CraftRequest("minecraft:stick")).join();
        assertEquals("p", legacyPlayerId.get());
        assertRefusedAsUnavailable(port.craft(ActorRef.body("b", true), new CraftRequest("minecraft:stick")));
    }

    @Test
    void machineOperationFallsBackForAPlayerAndRefusesABody() {
        BlockMachinePort port = new BlockMachinePort() {
            @Override
            public CompletableFuture<MachineOpResult> operate(String playerId, MachineOpRequest request) {
                legacyPlayerId.set(playerId);
                return CompletableFuture.completedFuture(
                        new MachineOpResult("minecraft:furnace", request.operation(), "minecraft:coal", 1, "inserted"));
            }
        };

        port.operate(ActorRef.player("p", true), new MachineOpRequest(0, 0, 0, "insert_fuel", 0)).join();
        assertEquals("p", legacyPlayerId.get());
        assertRefusedAsUnavailable(
                port.operate(ActorRef.body("b", true), new MachineOpRequest(0, 0, 0, "insert_fuel", 0)));
    }

    private static void assertRefusedAsUnavailable(CompletableFuture<?> call) {
        ExecutionException failure = assertThrows(ExecutionException.class, call::get);
        ToolException refusal = assertInstanceOf(ToolException.class, failure.getCause());
        assertEquals(ToolError.UNAVAILABLE, refusal.code());
    }
}