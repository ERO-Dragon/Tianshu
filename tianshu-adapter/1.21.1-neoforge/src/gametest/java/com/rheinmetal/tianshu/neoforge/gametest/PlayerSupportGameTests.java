package com.rheinmetal.tianshu.neoforge.gametest;

import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.game.BlockMachinePort;
import com.rheinmetal.tianshu.api.game.BlockPlacePort;
import com.rheinmetal.tianshu.api.game.BlockPlaceRequest;
import com.rheinmetal.tianshu.api.game.ContainerTransferPort;
import com.rheinmetal.tianshu.api.game.ContainerTransferRequest;
import com.rheinmetal.tianshu.api.game.BlockInteractPort;
import com.rheinmetal.tianshu.api.game.BlockInteractRequest;
import com.rheinmetal.tianshu.api.game.CraftPort;
import com.rheinmetal.tianshu.api.game.CraftRequest;
import com.rheinmetal.tianshu.api.game.CraftResult;
import com.rheinmetal.tianshu.api.game.MachineOpRequest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The player-assistance operations, against a real server world.
 *
 * <p>The client bench can show that a bad request is refused; it cannot show that a good one happened, because the
 * effect lives in the world — items leave an inventory, a block appears, a furnace burns. That is what these assert:
 * the port calls the tools route to, then the world state afterwards.</p>
 *
 * <p>Every case drives the ports rather than the tools: the tool layer above is argument validation and routing, which
 * the bench already covers, while "did it actually move" is only answerable here. The futures complete on the server
 * thread, so nothing blocks — each case waits through the harness and asserts in a later tick.</p>
 */
@GameTestHolder(TianshuGameTests.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PlayerSupportGameTests {
    private static final String PLAZA = "plaza";
    /** Long enough for a furnace to finish one item (200 ticks) with room for the round trips around it. */
    private static final int MACHINE_TICKS = 600;

    @GameTest(template = PLAZA, timeoutTicks = 200)
    public void placingABlockPutsItInTheWorld(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        player.getInventory().add(new ItemStack(Items.DIRT, 8));
        BlockPos target = new BlockPos(2, 1, 2);
        BlockPos targetAbsolute = helper.absolutePos(target);
        BlockPlacePort port = port(BlockPlacePort.class, helper);

        AtomicReference<Throwable> refused = new AtomicReference<>();
        AtomicReference<Boolean> placed = new AtomicReference<>();
        AtomicReference<String> placedMessage = new AtomicReference<>("");
        port.place(ActorRef.player(player.getUUID().toString(), true),
                        new BlockPlaceRequest(targetAbsolute.getX(), targetAbsolute.getY(), targetAbsolute.getZ(),
                                "minecraft:dirt", com.rheinmetal.tianshu.api.entity.BlockFace.UP))
                .whenComplete((result, error) -> {
                    if (error != null) {
                        refused.set(error);
                        return;
                    }
                    placed.set(result.placed());
                    placedMessage.set(result.message());
                });

        helper.startSequence()
                .thenWaitUntil(await(() -> placed.get() != null || refused.get() != null, "block.place"))
                .thenExecute(() -> {
                    if (refused.get() != null) {
                        helper.fail("placing was refused: " + reasonOf(refused.get()));
                        return;
                    }
                    if (!Boolean.TRUE.equals(placed.get())) {
                        helper.fail("the port did not place it: " + placedMessage.get());
                        return;
                    }
                    helper.assertBlockPresent(Blocks.DIRT, target);
                    if (player.getInventory().countItem(Items.DIRT) != 7) {
                        helper.fail("the block was placed but no item was consumed: dirt left = "
                                + player.getInventory().countItem(Items.DIRT));
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = PLAZA, timeoutTicks = 200)
    public void transferringFromAContainerMovesTheStack(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        BlockPos chest = new BlockPos(1, 1, 1);
        helper.setBlock(chest, Blocks.CHEST);
        BaseContainerBlockEntity container = helper.getBlockEntity(chest);
        container.setItem(0, new ItemStack(Items.DIAMOND, 5));
        ContainerTransferPort port = port(ContainerTransferPort.class, helper);

        AtomicReference<Throwable> refused = new AtomicReference<>();
        AtomicReference<Integer> moved = new AtomicReference<>();
        port.transfer(ActorRef.player(player.getUUID().toString(), true),
                        new ContainerTransferRequest(helper.absolutePos(chest).getX(),
                                helper.absolutePos(chest).getY(), helper.absolutePos(chest).getZ(),
                                0, 0, 5, true))
                .whenComplete((result, error) -> {
                    if (error != null) {
                        refused.set(error);
                        return;
                    }
                    moved.set(result.moved());
                });

        helper.startSequence()
                .thenWaitUntil(await(() -> moved.get() != null || refused.get() != null, "container.transfer"))
                .thenExecute(() -> {
                    if (refused.get() != null) {
                        helper.fail("the transfer was refused: " + reasonOf(refused.get()));
                        return;
                    }
                    if (!Integer.valueOf(5).equals(moved.get())) {
                        helper.fail("moved " + moved.get() + " instead of 5");
                        return;
                    }
                    if (!container.getItem(0).isEmpty()) {
                        helper.fail("the chest still holds " + container.getItem(0));
                        return;
                    }
                    if (player.getInventory().countItem(Items.DIAMOND) != 5) {
                        helper.fail("the player did not receive the diamonds: "
                                + player.getInventory().countItem(Items.DIAMOND));
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = PLAZA, timeoutTicks = 200)
    public void craftingAtATableConsumesTheIngredients(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.CRAFTING_TABLE);
        player.getInventory().add(new ItemStack(Items.OAK_PLANKS, 4));
        CraftPort port = port(CraftPort.class, helper);

        AtomicReference<Throwable> refused = new AtomicReference<>();
        AtomicReference<CraftResult> crafted = new AtomicReference<>();
        port.craft(ActorRef.player(player.getUUID().toString(), true),
                        new CraftRequest("minecraft:crafting_table"))
                .whenComplete((result, error) -> {
                    if (error != null) {
                        refused.set(error);
                        return;
                    }
                    crafted.set(result);
                });

        helper.startSequence()
                .thenWaitUntil(await(() -> crafted.get() != null || refused.get() != null, "craft.recipe"))
                .thenExecute(() -> {
                    if (refused.get() != null) {
                        helper.fail("crafting was refused: " + reasonOf(refused.get()));
                        return;
                    }
                    CraftResult result = crafted.get();
                    if (!result.crafted()) {
                        helper.fail("crafting did not happen: " + result.message());
                        return;
                    }
                    if (!"minecraft:crafting_table".equals(result.itemId())) {
                        helper.fail("crafted " + result.itemId() + " instead");
                        return;
                    }
                    int planks = player.getInventory().countItem(Items.OAK_PLANKS);
                    if (planks != 0) {
                        helper.fail("the ingredients were not consumed: planks left = " + planks);
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = PLAZA, timeoutTicks = MACHINE_TICKS)
    public void aFurnaceBurnsFuelIntoOutput(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        BlockPos furnace = new BlockPos(1, 1, 1);
        helper.setBlock(furnace, Blocks.FURNACE);
        player.getInventory().add(new ItemStack(Items.COAL, 4));
        player.getInventory().add(new ItemStack(Items.RAW_IRON, 2));
        BlockMachinePort port = port(BlockMachinePort.class, helper);
        BlockPos at = helper.absolutePos(furnace);
        ActorRef actor = ActorRef.player(player.getUUID().toString(), true);

        AtomicReference<Throwable> refused = new AtomicReference<>();
        AtomicReference<Integer> step = new AtomicReference<>(0);
        // insert_fuel -> insert_input -> wait for the burn -> take_output. Each step starts the next one when it
        // completes, so nothing blocks the server thread.
        port.operate(actor, new MachineOpRequest(at.getX(), at.getY(), at.getZ(), "insert_fuel", 0))
                .whenComplete((result, error) -> {
                    if (error != null) {
                        refused.set(error);
                        return;
                    }
                    port.operate(actor, new MachineOpRequest(at.getX(), at.getY(), at.getZ(), "insert_input", 1))
                            .whenComplete((second, secondError) -> {
                                if (secondError != null) {
                                    refused.set(secondError);
                                    return;
                                }
                                step.set(1);
                            });
                });

        helper.startSequence()
                .thenWaitUntil(await(() -> step.get() == 1 || refused.get() != null, "the fuel and the input"))
                .thenExecute(() -> {
                    if (refused.get() != null) {
                        helper.fail("loading the furnace was refused: " + reasonOf(refused.get()));
                    }
                })
                // The burn is a world process: give it its 200 ticks plus slack before reading the output.
                .thenIdle(240)
                .thenExecute(() -> {
                    port.operate(actor, new MachineOpRequest(at.getX(), at.getY(), at.getZ(), "take_output", 0))
                            .whenComplete((taken, error) -> {
                                if (error != null) {
                                    refused.set(error);
                                    return;
                                }
                                step.set(2);
                            });
                })
                .thenWaitUntil(await(() -> step.get() == 2 || refused.get() != null, "take_output"))
                .thenExecute(() -> {
                    if (refused.get() != null) {
                        helper.fail("taking the output was refused: " + reasonOf(refused.get()));
                        return;
                    }
                    int ingots = player.getInventory().countItem(Items.IRON_INGOT);
                    if (ingots != 1) {
                        helper.fail("expected one iron ingot in the inventory, found " + ingots);
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = PLAZA, timeoutTicks = 200)
    public void interactingWithABlockTogglesIt(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        BlockPos pos = new BlockPos(1, 1, 1);
        // The lever is given its whole state on purpose: setBlock with the default state puts a wall lever in the air,
        // which drops off before the interaction ever runs and would look like a failure of the port.
        helper.setBlock(pos, Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.FLOOR)
                .setValue(LeverBlock.FACING, Direction.NORTH)
                .setValue(LeverBlock.POWERED, Boolean.FALSE));
        BlockPos at = helper.absolutePos(pos);
        BlockInteractPort port = port(BlockInteractPort.class, helper);

        AtomicReference<Throwable> refused = new AtomicReference<>();
        AtomicReference<Boolean> interacted = new AtomicReference<>();
        port.interact(player.getUUID().toString(), new BlockInteractRequest(at.getX(), at.getY(), at.getZ()))
                .whenComplete((result, error) -> {
                    if (error != null) {
                        refused.set(error);
                        return;
                    }
                    interacted.set(result.interacted());
                });

        helper.startSequence()
                .thenWaitUntil(await(() -> interacted.get() != null || refused.get() != null, "block.interact"))
                .thenExecute(() -> {
                    if (refused.get() != null) {
                        helper.fail("the interaction was refused: " + reasonOf(refused.get()));
                        return;
                    }
                    if (!Boolean.TRUE.equals(interacted.get())) {
                        helper.fail("the port did not interact");
                        return;
                    }
                    if (!helper.getBlockState(pos).getValue(LeverBlock.POWERED)) {
                        helper.fail("the port reported an interaction but the lever did not toggle");
                    }
                })
                .thenSucceed();
    }

    private static ServerPlayer mockPlayer(GameTestHelper helper) {
        // A server player in the level, not the bare mock player: the ports resolve their actor through the server,
        // and an entity the server cannot find is not someone who can act.
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        if (player == null) {
            helper.fail("the harness did not give a server player");
            throw new IllegalStateException("no server player: the harness refused");
        }
        // Where the player stands is part of the fixture: every operation here is bounded by reach, so a player left at
        // the level spawn would be refused for the wrong reason. Creative is the other half of that: in creative nothing
        // is consumed, so "did the item actually leave the inventory" would pass without the item ever leaving.
        player.setGameMode(GameType.SURVIVAL);
        BlockPos stand = helper.absolutePos(new BlockPos(0, 1, 0));
        player.moveTo(stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D, 0.0F, 0.0F);
        return player;
    }

    private static <T> T port(Class<T> type, GameTestHelper helper) {
        T found = TianshuRuntimePorts.find(type).orElse(null);
        if (found == null) {
            helper.fail("no " + type.getSimpleName() + " installed: the server session never registered it");
            throw new IllegalStateException("missing port " + type.getSimpleName());
        }
        return found;
    }

    /**
     * A wait for the harness: it re-runs this every tick and treats a throw as "not yet", so the check must only throw
     * while the answer is missing. Blocking here would deadlock — the port completes on this very thread.
     */
    private static Runnable await(java.util.function.BooleanSupplier ready, String what) {
        return () -> {
            if (!ready.getAsBoolean()) {
                throw new GameTestAssertException("still waiting for " + what);
            }
        };
    }

    private static String reasonOf(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}