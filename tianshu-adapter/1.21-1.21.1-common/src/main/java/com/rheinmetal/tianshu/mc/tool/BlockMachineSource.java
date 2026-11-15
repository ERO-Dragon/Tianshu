package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.game.BlockMachinePort;
import com.rheinmetal.tianshu.api.game.MachineOpRequest;
import com.rheinmetal.tianshu.api.game.MachineOpResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.CompletableFuture;

/**
 * The 1.21.1 machine operation, limited to the furnace family.
 *
 * <p>Each operation only touches the slot it names and only after checking that the item belongs there: fuel must
 * be fuel, input must have a smelting recipe, and the result slot must have something to take. Any other block
 * entity is refused with a reason.</p>
 *
 * <p>A player and an agent body follow the same rules; only the 36-slot store behind {@code playerSlot} differs.</p>
 */
public final class BlockMachineSource implements BlockMachinePort {

    private static final double MAX_DISTANCE_SQUARED = 36.0;
    private static final int SLOT_INPUT = 0;
    private static final int SLOT_FUEL = 1;
    private static final int SLOT_RESULT = 2;
    private static final int MAIN_INVENTORY_SIZE = 36;

    private final MinecraftServer server;

    public BlockMachineSource(MinecraftServer server) {
        this.server = server;
    }

    @Override
    @Deprecated
    public CompletableFuture<MachineOpResult> operate(String playerId, MachineOpRequest request) {
        return operate(ActorRef.player(playerId, false), request);
    }

    @Override
    public CompletableFuture<MachineOpResult> operate(ActorRef actor, MachineOpRequest request) {
        CompletableFuture<MachineOpResult> result = new CompletableFuture<>();
        server.execute(() -> result.complete(operateNow(actor, request)));
        return result;
    }

    private MachineOpResult operateNow(ActorRef actor, MachineOpRequest request) {
        ToolActor holder = ToolActors.resolve(server, actor).orElse(null);
        if (holder == null) {
            return failure("", request, actor.kind() == ActorRef.Kind.BODY
                    ? "the agent body is not present"
                    : "the player is not online");
        }
        Container inventory = holder.inventory();
        if (inventory == null) {
            return failure("", request, "that agent body has no inventory");
        }
        ServerLevel level = holder.level();
        BlockPos pos = new BlockPos(request.x(), request.y(), request.z());
        if (!level.hasChunkAt(pos)) {
            return failure("", request, "that chunk is not loaded");
        }
        if (distanceSquared(holder.position(), pos) > MAX_DISTANCE_SQUARED) {
            return failure("", request, "the machine is out of reach");
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        ResourceLocation machineId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
        String machine = machineId == null ? "" : machineId.toString();
        if (!(blockEntity instanceof AbstractFurnaceBlockEntity furnace)) {
            return failure(machine, request, "only furnace-family machines are supported");
        }
        return switch (request.operation()) {
            case "insert_fuel" -> insertFuel(inventory, furnace, machine, request);
            case "insert_input" -> insertInput(inventory, level, furnace, machine, request);
            case "take_output" -> takeOutput(inventory, furnace, machine, request);
            default -> failure(machine, request, "unknown operation");
        };
    }

    private MachineOpResult insertFuel(Container inventory, AbstractFurnaceBlockEntity furnace, String machine,
                                       MachineOpRequest request) {
        ItemStack source = inventory.getItem(request.playerSlot());
        if (source.isEmpty()) {
            return failure(machine, request, "the actor slot is empty");
        }
        if (!AbstractFurnaceBlockEntity.getFuel().containsKey(source.getItem())) {
            return failure(machine, request, "that item is not a fuel");
        }
        int moved = moveIntoSlot(furnace, SLOT_FUEL, source, source.getCount());
        if (moved <= 0) {
            return failure(machine, request, "the fuel slot is full");
        }
        source.shrink(moved);
        inventory.setChanged();
        return new MachineOpResult(machine, request.operation(), itemId(source), moved, "inserted");
    }

    private MachineOpResult insertInput(Container inventory, ServerLevel level, AbstractFurnaceBlockEntity furnace,
                                        String machine, MachineOpRequest request) {
        ItemStack source = inventory.getItem(request.playerSlot());
        if (source.isEmpty()) {
            return failure(machine, request, "the actor slot is empty");
        }
        boolean smeltable = level.getRecipeManager()
                .getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(source), level)
                .isPresent();
        if (!smeltable) {
            return failure(machine, request, "that item has no smelting recipe");
        }
        int moved = moveIntoSlot(furnace, SLOT_INPUT, source, source.getCount());
        if (moved <= 0) {
            return failure(machine, request, "the input slot is full");
        }
        source.shrink(moved);
        inventory.setChanged();
        return new MachineOpResult(machine, request.operation(), itemId(source), moved, "inserted");
    }

    private MachineOpResult takeOutput(Container inventory, AbstractFurnaceBlockEntity furnace, String machine,
                                       MachineOpRequest request) {
        ItemStack result = furnace.getItem(SLOT_RESULT);
        if (result.isEmpty()) {
            return failure(machine, request, "there is nothing to take");
        }
        int moved = addToMainInventory(inventory, result, result.getCount());
        if (moved <= 0) {
            return failure(machine, request, "the actor's inventory is full");
        }
        result.shrink(moved);
        furnace.setChanged();
        inventory.setChanged();
        return new MachineOpResult(machine, request.operation(), itemId(result), moved, "taken");
    }

    private static int moveIntoSlot(AbstractFurnaceBlockEntity furnace, int slot, ItemStack source, int wanted) {
        ItemStack target = furnace.getItem(slot);
        if (!target.isEmpty() && !ItemStack.isSameItemSameComponents(target, source)) {
            return 0;
        }
        int room = target.isEmpty() ? source.getMaxStackSize() : source.getMaxStackSize() - target.getCount();
        int moved = Math.min(wanted, room);
        if (moved <= 0) {
            return 0;
        }
        if (target.isEmpty()) {
            ItemStack placed = source.copy();
            placed.setCount(moved);
            furnace.setItem(slot, placed);
        } else {
            target.grow(moved);
        }
        furnace.setChanged();
        return moved;
    }

    private static int addToMainInventory(Container inventory, ItemStack source, int wanted) {
        int remaining = wanted;
        for (int slot = 0; slot < MAIN_INVENTORY_SIZE && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, source)) {
                continue;
            }
            int room = Math.min(stack.getMaxStackSize() - stack.getCount(), remaining);
            if (room > 0) {
                stack.grow(room);
                remaining -= room;
            }
        }
        for (int slot = 0; slot < MAIN_INVENTORY_SIZE && remaining > 0; slot++) {
            if (!inventory.getItem(slot).isEmpty()) {
                continue;
            }
            int placed = Math.min(source.getMaxStackSize(), remaining);
            ItemStack copy = source.copy();
            copy.setCount(placed);
            inventory.setItem(slot, copy);
            remaining -= placed;
        }
        return wanted - remaining;
    }

    private static double distanceSquared(Vec3 from, BlockPos pos) {
        double dx = from.x - (pos.getX() + 0.5);
        double dy = from.y - (pos.getY() + 0.5);
        double dz = from.z - (pos.getZ() + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }

    private static String itemId(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "" : id.toString();
    }

    private static MachineOpResult failure(String machine, MachineOpRequest request, String message) {
        return new MachineOpResult(machine, request.operation(), "", 0, message);
    }
}