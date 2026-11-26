package com.rheinmetal.tianshu.neoforge.gametest;

import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.entity.ActionOutcome;
import com.rheinmetal.tianshu.api.entity.ActorPosition;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.EntityActions;
import com.rheinmetal.tianshu.api.entity.EntityActor;
import com.rheinmetal.tianshu.api.entity.EntityActors;
import com.rheinmetal.tianshu.api.entity.EntityItemPort;
import com.rheinmetal.tianshu.api.entity.EntityItemUsePort;
import com.rheinmetal.tianshu.api.entity.EntitySlot;
import com.rheinmetal.tianshu.api.entity.EntitySpawnPort;
import com.rheinmetal.tianshu.api.entity.EquipRequest;
import com.rheinmetal.tianshu.api.entity.ItemUseTarget;
import com.rheinmetal.tianshu.api.game.BlockMachinePort;
import com.rheinmetal.tianshu.api.game.BlockPlacePort;
import com.rheinmetal.tianshu.api.game.BlockPlaceRequest;
import com.rheinmetal.tianshu.api.game.ContainerTransferPort;
import com.rheinmetal.tianshu.api.game.ContainerTransferRequest;
import com.rheinmetal.tianshu.api.game.ActorStatus;
import com.rheinmetal.tianshu.api.game.ActorStatusPort;
import com.rheinmetal.tianshu.api.game.BlockInspectPort;
import com.rheinmetal.tianshu.api.game.BlockInspectRequest;
import com.rheinmetal.tianshu.api.game.BlockInspectResult;
import com.rheinmetal.tianshu.api.game.CommandApprovals;
import com.rheinmetal.tianshu.api.game.CommandExecutionPort;
import com.rheinmetal.tianshu.api.game.CommandRequest;
import com.rheinmetal.tianshu.api.game.CommandResult;
import com.rheinmetal.tianshu.api.game.BlockInteractPort;
import com.rheinmetal.tianshu.api.game.EntityInspectPort;
import com.rheinmetal.tianshu.api.game.EntityInspectRequest;
import com.rheinmetal.tianshu.api.game.EntityInspectResult;
import com.rheinmetal.tianshu.api.game.ItemInspectPort;
import com.rheinmetal.tianshu.api.game.ItemInspectRequest;
import com.rheinmetal.tianshu.api.game.ItemInspectResult;
import com.rheinmetal.tianshu.api.game.BlockInteractRequest;
import com.rheinmetal.tianshu.api.game.CraftPort;
import com.rheinmetal.tianshu.api.game.CraftableRecipe;
import com.rheinmetal.tianshu.api.game.CraftableRecipeList;
import com.rheinmetal.tianshu.api.game.CraftRequest;
import com.rheinmetal.tianshu.api.game.CraftResult;
import com.rheinmetal.tianshu.api.game.LocateRequest;
import com.rheinmetal.tianshu.api.game.LocateResult;
import com.rheinmetal.tianshu.api.game.MachineOpRequest;
import com.rheinmetal.tianshu.api.game.RecipeAvailabilityPort;
import com.rheinmetal.tianshu.api.game.WorldInfo;
import com.rheinmetal.tianshu.api.game.WorldInfoPort;
import com.rheinmetal.tianshu.api.game.WorldLocatePort;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
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

    @GameTest(template = PLAZA, timeoutTicks = 300)
    public void aBodyUsesTheItemInItsHand(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        EntitySpawnPort spawn = port(EntitySpawnPort.class, helper);
        BlockPos at = helper.absolutePos(new BlockPos(0, 1, 0));
        EntityActor body;
        try {
            body = spawn.spawn(TianshuGameTests.MOD_ID, "walker",
                            new ActorPosition(helper.getLevel().dimension().location().toString(),
                                    at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D), false)
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception refused) {
            helper.fail("the body could not be spawned: " + reasonOf(refused));
            return;
        }
        try {
            // The item has to arrive by the body's own route: drop it in the world, pick it up, put it in the hand. An
            // inventory written from the outside would prove nothing about the ports.
            helper.getLevel().addFreshEntity(new ItemEntity(helper.getLevel(),
                    at.getX() + 0.5D, at.getY() + 0.2D, at.getZ() + 0.5D,
                    new ItemStack(Items.SNOWBALL, 4)));
            EntityActions actions = EntityActors.actions(body.id()).orElse(null);
            EntityItemPort items = actions == null ? null : actions.items().orElse(null);
            EntityItemUsePort itemUse = actions == null ? null : actions.itemUse().orElse(null);
            if (items == null || itemUse == null) {
                helper.fail("the carrier offers no item ports");
                return;
            }
            ActorRef actor = ActorRef.body(body.id(), false);
            AtomicReference<Throwable> refused = new AtomicReference<>();
            AtomicReference<Integer> step = new AtomicReference<>(0);
            items.pickup(actor, 2).whenComplete((picked, error) -> {
                if (error != null) {
                    refused.set(error);
                    return;
                }
                items.equip(actor, new EquipRequest(0, EntitySlot.MAIN_HAND)).whenComplete((equipped, equipError) -> {
                    if (equipError != null) {
                        refused.set(equipError);
                        return;
                    }
                    itemUse.use(actor, ItemUseTarget.Air.INSTANCE, 0).whenComplete((used, useError) -> {
                        if (useError != null) {
                            refused.set(useError);
                            return;
                        }
                        if (used.outcome() == ActionOutcome.DONE) {
                            step.set(1);
                        } else {
                            refused.set(new AssertionError("item.use ended as " + used.outcome()
                                    + ": " + used.detail()));
                        }
                    });
                });
            });
            helper.startSequence()
                    .thenWaitUntil(await(() -> step.get() == 1 || refused.get() != null, "item.use"))
                    .thenExecute(() -> {
                        if (refused.get() != null) {
                            helper.fail("using the item failed: " + reasonOf(refused.get()));
                            return;
                        }
                        // A thrown snowball is the world evidence: the item left the hand and became an entity.
                        List<Snowball> thrown = helper.getLevel().getEntitiesOfClass(Snowball.class,
                                new AABB(at).inflate(16.0D));
                        if (thrown.isEmpty()) {
                            helper.fail("the body reported a use but no snowball exists in the world");
                        }
                    })
                    .thenSucceed();
        } finally {
            try {
                spawn.release(TianshuGameTests.MOD_ID, body.id()).get(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // The verdict is already decided above.
            }
        }
    }

    @GameTest(template = PLAZA, timeoutTicks = 200)
    public void inspectingABlockReadsWhatItHoldsWithoutOpeningIt(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        BlockPos chest = new BlockPos(1, 1, 1);
        helper.setBlock(chest, Blocks.CHEST);
        BaseContainerBlockEntity container = helper.getBlockEntity(chest);
        container.setItem(0, new ItemStack(Items.DIAMOND, 5));
        container.setItem(3, new ItemStack(Items.OAK_LOG, 2));
        BlockPos at = helper.absolutePos(chest);
        BlockInspectPort port = port(BlockInspectPort.class, helper);

        AtomicReference<Throwable> refused = new AtomicReference<>();
        AtomicReference<BlockInspectResult> read = new AtomicReference<>();
        port.inspect(ActorRef.player(player.getUUID().toString(), true),
                        new BlockInspectRequest(at.getX(), at.getY(), at.getZ()))
                .whenComplete((result, error) -> {
                    if (error != null) {
                        refused.set(error);
                        return;
                    }
                    read.set(result);
                });

        helper.startSequence()
                .thenWaitUntil(await(() -> read.get() != null || refused.get() != null, "block.inspect"))
                .thenExecute(() -> {
                    if (refused.get() != null) {
                        helper.fail("the block could not be read: " + reasonOf(refused.get()));
                        return;
                    }
                    BlockInspectResult result = read.get();
                    if (!"minecraft:chest".equals(result.blockId())) {
                        helper.fail("read " + result.blockId() + " instead of a chest");
                        return;
                    }
                    // The failure this guards against: an id and a state with nothing inside, which is what a
                    // GUI-bound read gives a caller that never opened the menu.
                    if (result.items().size() != 2) {
                        helper.fail("expected two occupied slots, got " + result.items().size() + ": " + result.items());
                        return;
                    }
                    if (!result.items().stream().anyMatch(entry -> entry.slot() == 0
                            && "minecraft:diamond".equals(entry.itemId()) && entry.count() == 5)) {
                        helper.fail("the diamonds in slot 0 were not reported: " + result.items());
                        return;
                    }
                    if (result.energy().present()) {
                        helper.fail("a vanilla chest reported an energy buffer: " + result.energy());
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = PLAZA, timeoutTicks = 200)
    public void readingAnActorsOwnStateReportsLiveHealth(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        ActorStatusPort port = port(ActorStatusPort.class, helper);
        String actorId = player.getUUID().toString();

        // The read is taken twice on purpose: the second one is after the body's state was changed, so a status that was
        // cached once or filled with defaults cannot pass. Health is set rather than dealt as damage because a mock
        // player is invulnerable, and "damage did nothing" would test the harness instead of the read.
        ActorStatus first = awaitStatus(helper, port, actorId);
        player.setHealth(15.0F);
        player.getFoodData().setFoodLevel(4);
        ActorStatus afterChange = awaitStatus(helper, port, actorId);

        if (!actorId.equals(afterChange.id())) {
            helper.fail("read status for " + afterChange.id() + " instead of the player");
            return;
        }
        if (!"survival".equals(afterChange.gameMode())) {
            helper.fail("expected survival, read " + afterChange.gameMode());
            return;
        }
        if (!afterChange.alive()) {
            helper.fail("the player is alive but the status says otherwise");
            return;
        }
        if (!(first.health() > afterChange.health())) {
            helper.fail("health did not follow the change: " + first.health() + " then " + afterChange.health());
        }
        if (afterChange.health() != 15.0F) {
            helper.fail("health was set to 15 but the status reads " + afterChange.health());
            return;
        }
        if (afterChange.foodLevel() != 4) {
            helper.fail("hunger was set to 4 but the status reads " + afterChange.foodLevel());
        }
        if (afterChange.dimension().isBlank()) {
            helper.fail("the status has no dimension");
        }
    }

    @GameTest(template = PLAZA, timeoutTicks = 200)
    public void readingTheWorldAroundAnActorReportsItsPositionAndTime(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        WorldInfoPort port = port(WorldInfoPort.class, helper);
        String expectedDimension = player.serverLevel().dimension().location().toString();

        AtomicReference<WorldInfo> read = new AtomicReference<>();
        AtomicReference<Throwable> refused = new AtomicReference<>();
        port.info(ActorRef.player(player.getUUID().toString(), true)).whenComplete((info, error) -> {
            if (error != null) {
                refused.set(error);
                return;
            }
            read.set(info);
        });
        helper.startSequence()
                .thenWaitUntil(await(() -> read.get() != null || refused.get() != null, "world.info"))
                .thenExecute(() -> {
                    if (refused.get() != null) {
                        helper.fail("the world could not be read: " + reasonOf(refused.get()));
                        return;
                    }
                    WorldInfo info = read.get();
                    if (!expectedDimension.equals(info.dimension())) {
                        helper.fail("read dimension " + info.dimension() + " instead of " + expectedDimension);
                        return;
                    }
                    if (info.biome().isBlank()) {
                        helper.fail("the world read has no biome");
                        return;
                    }
                    if (info.difficulty().isBlank()) {
                        helper.fail("the world read has no difficulty");
                        return;
                    }
                    if (info.lightLevel() < 0 || info.lightLevel() > 15) {
                        helper.fail("light level out of range: " + info.lightLevel());
                    }
                })
                .thenSucceed();
    }

    private static ActorStatus awaitStatus(GameTestHelper helper, ActorStatusPort port, String actorId) {
        AtomicReference<ActorStatus> read = new AtomicReference<>();
        AtomicReference<Throwable> refused = new AtomicReference<>();
        port.status(ActorRef.player(actorId, true)).whenComplete((status, error) -> {
            if (error != null) {
                refused.set(error);
                return;
            }
            read.set(status);
        });
        helper.startSequence()
                .thenWaitUntil(await(() -> read.get() != null || refused.get() != null, "body.status"))
                .thenExecute(() -> {
                    if (refused.get() != null) {
                        helper.fail("the status could not be read: " + reasonOf(refused.get()));
                    }
                })
                .thenSucceed();
        return read.get();
    }

    @GameTest(template = PLAZA, timeoutTicks = 300)
    public void locatingReportsWhatItSearchedAndWhatItFound(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        WorldLocatePort port = port(WorldLocatePort.class, helper);
        ActorRef actor = ActorRef.player(player.getUUID().toString(), true);
        String here = helper.getLevel().getBiome(helper.absolutePos(new BlockPos(0, 1, 0))).unwrapKey()
                .map(key -> key.location().toString())
                .orElse("");

        // Refusals first: an unknown id must be a refusal with a reason, not an empty search that reads as "none
        // nearby". These are the two mistakes a caller can make and they must look different.
        LocateResult unknown = awaitLocate(helper, port, actor, new LocateRequest("tianshu:no_such_structure", "", 64));
        if (unknown.searched()) {
            helper.fail("an unknown structure id was reported as a search instead of a refusal: " + unknown.message());
            return;
        }
        LocateResult both = awaitLocate(helper, port, actor,
                new LocateRequest("minecraft:village_plains", "minecraft:plains", 64));
        if (both.searched()) {
            helper.fail("a request naming both a structure and a biome was searched instead of refused");
            return;
        }

        // A biome search that must succeed: the biome the test stands in.
        if (here.isBlank()) {
            helper.fail("the test could not read its own biome");
            return;
        }
        LocateResult biome = awaitLocate(helper, port, actor, new LocateRequest("", here, 64));
        if (!biome.searched() || !biome.found() || biome.sampled() <= 0) {
            helper.fail("the biome under the test was not found in its own sample: " + biome.message());
            return;
        }
        if (biome.distanceBlocks() > 64.0D) {
            helper.fail("the found biome is farther than the search radius: " + biome.distanceBlocks());
        }
        if (biome.x() == 0 && biome.z() == 0) {
            helper.fail("the biome hit has no position");
        }
    }

    private static LocateResult awaitLocate(GameTestHelper helper, WorldLocatePort port, ActorRef actor,
                                            LocateRequest request) {
        AtomicReference<LocateResult> read = new AtomicReference<>();
        AtomicReference<Throwable> refused = new AtomicReference<>();
        port.locate(actor, request).whenComplete((result, error) -> {
            if (error != null) {
                refused.set(error);
                return;
            }
            read.set(result);
        });
        helper.startSequence()
                .thenWaitUntil(await(() -> read.get() != null || refused.get() != null, "world.locate"))
                .thenExecute(() -> {
                    if (refused.get() != null) {
                        helper.fail("locating failed: " + reasonOf(refused.get()));
                    }
                })
                .thenSucceed();
        return read.get();
    }

    @GameTest(template = PLAZA, timeoutTicks = 200)
    public void listingWhatTheBodyCouldCraftMatchesItsInventory(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        RecipeAvailabilityPort port = port(RecipeAvailabilityPort.class, helper);
        ActorRef actor = ActorRef.player(player.getUUID().toString(), true);

        // Empty inventory: nothing craftable. This is the half that catches a search that ignores the inventory.
        CraftableRecipeList empty = awaitCraftable(helper, port, actor, 20);
        if (!empty.recipes().isEmpty()) {
            helper.fail("an empty inventory was reported as able to craft " + empty.recipes().size() + " recipes");
            return;
        }

        // Two planks make sticks in the 2x2 grid; four planks make a crafting table, which needs the 3x3 grid.
        player.getInventory().add(new ItemStack(Items.OAK_PLANKS, 8));
        CraftableRecipeList withPlanks = awaitCraftable(helper, port, actor, 64);
        CraftableRecipe stickRecipe = withPlanks.recipes().stream()
                .filter(recipe -> "minecraft:stick".equals(recipe.outputItemId()))
                .findFirst()
                .orElse(null);
        CraftableRecipe tableRecipe = withPlanks.recipes().stream()
                .filter(recipe -> "minecraft:crafting_table".equals(recipe.outputItemId()))
                .findFirst()
                .orElse(null);
        if (stickRecipe == null) {
            helper.fail("sticks are not reported as craftable from 8 planks: " + withPlanks.recipes());
            return;
        }
        if (stickRecipe.needsTable()) {
            helper.fail("sticks fit a 2x2 grid but were reported as needing a table");
            return;
        }
        if (tableRecipe == null) {
            helper.fail("a crafting table is not reported as craftable from 8 planks");
            return;
        }
        // Vanilla's crafting table is itself a 2x2 recipe, so it must NOT need a table. The 3x3 half is the pickaxe.
        if (tableRecipe.needsTable()) {
            helper.fail("a crafting table is a 2x2 recipe but was reported as needing a table");
            return;
        }
        player.getInventory().add(new ItemStack(Items.STICK, 4));
        CraftableRecipeList withSticks = awaitCraftable(helper, port, actor, 64);
        CraftableRecipe pickaxe = withSticks.recipes().stream()
                .filter(recipe -> "minecraft:wooden_pickaxe".equals(recipe.outputItemId()))
                .findFirst()
                .orElse(null);
        if (pickaxe == null) {
            helper.fail("a wooden pickaxe is not craftable from planks and sticks: " + withSticks.recipes());
            return;
        }
        if (!pickaxe.needsTable()) {
            helper.fail("a wooden pickaxe is a 3x3 recipe but was reported as not needing a table");
        }

        // The limit is part of the contract: a caller that asks for one gets one, and knows it may be truncated.
        CraftableRecipeList one = awaitCraftable(helper, port, actor, 1);
        if (one.recipes().size() != 1) {
            helper.fail("limit 1 returned " + one.recipes().size() + " recipes");
            return;
        }
        if (!one.truncated()) {
            helper.fail("a capped answer did not report itself as truncated");
        }
    }

    private static CraftableRecipeList awaitCraftable(GameTestHelper helper, RecipeAvailabilityPort port,
                                                      ActorRef actor, int limit) {
        AtomicReference<CraftableRecipeList> read = new AtomicReference<>();
        AtomicReference<Throwable> refused = new AtomicReference<>();
        port.available(actor, limit).whenComplete((list, error) -> {
            if (error != null) {
                refused.set(error);
                return;
            }
            read.set(list);
        });
        helper.startSequence()
                .thenWaitUntil(await(() -> read.get() != null || refused.get() != null, "craft.available"))
                .thenExecute(() -> {
                    if (refused.get() != null) {
                        helper.fail("listing craftable recipes failed: " + reasonOf(refused.get()));
                    }
                })
                .thenSucceed();
        return read.get();
    }


    @GameTest(template = PLAZA, timeoutTicks = 200)
    public void readingAnEntityAndAStackReportsWhatDecisionsNeed(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        EntityInspectPort entityPort = port(EntityInspectPort.class, helper);
        ItemInspectPort itemPort = port(ItemInspectPort.class, helper);
        ActorRef actor = ActorRef.player(player.getUUID().toString(), true);

        // The entity half: the player is the one entity the test always has, and it is a living entity with health.
        AtomicReference<EntityInspectResult> entityRead = new AtomicReference<>();
        entityPort.inspect(actor, new EntityInspectRequest(player.getUUID().toString()))
                .whenComplete((read, error) -> entityRead.set(read));
        if (entityRead.get() == null || !entityRead.get().read()) {
            helper.fail("the player could not be inspected: "
                    + (entityRead.get() == null ? "no answer" : entityRead.get().message()));
            return;
        }
        if (!"minecraft:player".equals(entityRead.get().typeId())) {
            helper.fail("read type " + entityRead.get().typeId() + " instead of a player");
            return;
        }
        if (entityRead.get().health() != entityRead.get().maxHealth()) {
            helper.fail("a fresh player should be at full health: "
                    + entityRead.get().health() + "/" + entityRead.get().maxHealth());
            return;
        }
        if (entityRead.get().baby() || entityRead.get().tamed()) {
            helper.fail("a player was reported as a baby or as tamed");
            return;
        }

        // The stack half: a used pickaxe must report what is left, which is the number the decision needs.
        ItemStack pickaxe = new ItemStack(Items.STONE_PICKAXE);
        pickaxe.setDamageValue(3);
        player.getInventory().setItem(0, pickaxe);
        AtomicReference<ItemInspectResult> itemRead = new AtomicReference<>();
        itemPort.inspect(actor, new ItemInspectRequest(0)).whenComplete((read, error) -> itemRead.set(read));
        if (itemRead.get() == null || !itemRead.get().read()) {
            helper.fail("the pickaxe could not be inspected: "
                    + (itemRead.get() == null ? "no answer" : itemRead.get().message()));
            return;
        }
        if (!itemRead.get().damageable() || itemRead.get().damage() != 3) {
            helper.fail("damage was not reported: damageable=" + itemRead.get().damageable()
                    + " damage=" + itemRead.get().damage());
            return;
        }
        if (itemRead.get().remainingDurability() != itemRead.get().maxDamage() - 3) {
            helper.fail("remaining durability is wrong: " + itemRead.get().remainingDurability()
                    + " of " + itemRead.get().maxDamage());
            return;
        }

        // An empty slot is a refusal with a reason, not a stack with no name.
        AtomicReference<ItemInspectResult> emptyRead = new AtomicReference<>();
        itemPort.inspect(actor, new ItemInspectRequest(5)).whenComplete((read, error) -> emptyRead.set(read));
        if (emptyRead.get() == null || emptyRead.get().read() || emptyRead.get().message().isBlank()) {
            helper.fail("an empty slot did not come back as a refusal with a reason");
            return;
        }
        // Every read here completes inline on this thread (server.execute runs the task when it is already the server
        // thread), so the assertions above ran against real answers and the test can end.
        helper.succeed();
    }

    @GameTest(template = PLAZA, timeoutTicks = 200)
    public void aCommandRunsOnlyAfterAnOperatorApprovesThatExactLine(GameTestHelper helper) {
        ServerPlayer player = mockPlayer(helper);
        // The source is built with the level an operator would configure; the packaged default is 0 (a plain player),
        // which would make "time" look like an unknown command rather than a forbidden one.
        CommandExecutionPort port = new com.rheinmetal.tianshu.mc.command.CommandExecutionSource(
                helper.getLevel().getServer(), CommandApprovals.shared(), () -> 2);
        ActorRef actor = ActorRef.player(player.getUUID().toString(), true);
        ServerLevel level = player.serverLevel();

        // First call: nothing runs, and it says so with a token. A world change here would mean the approval is a
        // formality.
        CommandResult asked = awaitCommand(helper, port, actor, CommandRequest.pending("time set day"));
        if (asked.status() != CommandResult.Status.PENDING_APPROVAL || asked.approvalToken().isBlank()) {
            helper.fail("the first call did not ask for approval: " + asked);
            return;
        }

        // Retrying without the operator's yes must still not run.
        CommandResult unapproved = awaitCommand(helper, port, actor,
                CommandRequest.pending("time set day").withApproval(asked.approvalToken()));
        if (unapproved.status() != CommandResult.Status.REJECTED) {
            helper.fail("an unapproved token ran the command: " + unapproved);
            return;
        }

        CommandApprovals.shared().approve(asked.approvalToken());
        CommandResult ran = awaitCommand(helper, port, actor,
                CommandRequest.pending("time set day").withApproval(asked.approvalToken()));
        if (!ran.ran()) {
            helper.fail("an approved command did not run: " + ran.status() + " " + ran.message());
            return;
        }
        long dayTime = level.getDayTime() % 24000L;
        if (dayTime != 1000L) {
            helper.fail("the command ran but the world did not change: dayTime=" + dayTime);
            return;
        }

        // And the token is burned: the same approval cannot run a second command.
        CommandResult again = awaitCommand(helper, port, actor,
                CommandRequest.pending("time set day").withApproval(asked.approvalToken()));
        if (again.status() != CommandResult.Status.REJECTED) {
            helper.fail("a consumed approval ran again: " + again);
            return;
        }
        helper.succeed();
    }

    /** The port runs on the server thread when it is already there, so this does not need a tick sequence. */
    private static CommandResult awaitCommand(GameTestHelper helper, CommandExecutionPort port, ActorRef actor,
                                              CommandRequest request) {
        try {
            return port.run(actor, request).get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception failure) {
            helper.fail("running a command failed: " + failure);
            throw new IllegalStateException(failure);
        }
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
