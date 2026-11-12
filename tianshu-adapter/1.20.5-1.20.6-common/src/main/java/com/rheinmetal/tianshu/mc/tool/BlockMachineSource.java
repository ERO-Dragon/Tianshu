package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.game.BlockMachinePort;
import com.rheinmetal.tianshu.api.game.MachineOpRequest;
import com.rheinmetal.tianshu.api.game.MachineOpResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The 1.21.1 machine operation, limited to the furnace family.
 *
 * <p>Each operation only touches the slot it names and only after checking that the item belongs there: fuel must
 * be fuel, input must have a smelting recipe, and the result slot must have something to take. Any other block
 * entity is refused with a reason.</p>
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
    public CompletableFuture<MachineOpResult> operate(String playerId, MachineOpRequest request) {
        CompletableFuture<MachineOpResult> result = new CompletableFuture<>();
        server.execute(() -> result.complete(operateNow(playerId, request)));
        return result;
    }

    private MachineOpResult operateNow(String playerId, MachineOpRequest request) {
        ServerPlayer player = player(playerId);
        if (player == null) {
            return failure("", request, "the player is not online");
        }
        ServerLevel level = player.serverLevel();
        BlockPos pos = new BlockPos(request.x(), request.y(), request.z());
        if (!level.hasChunkAt(pos)) {
            return failure("", request, "that chunk is not loaded");
        }
        if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > MAX_DISTANCE_SQUARED) {
            return failure("", request, "the machine is out of reach");
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        ResourceLocation machineId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
        String machine = machineId == null ? "" : machineId.toString();
        if (!(blockEntity instanceof AbstractFurnaceBlockEntity furnace)) {
            return failure(machine, request, "only furnace-family machines are supported");
        }
        return switch (request.operation()) {
            case "insert_fuel" -> insertFuel(player, furnace, machine, request);
            case "insert_input" -> insertInput(player, level, furnace, machine, request);
            case "take_output" -> takeOutput(player, furnace, machine, request);
            default -> failure(machine, request, "unknown operation");
        };
    }

    private MachineOpResult insertFuel(ServerPlayer player, AbstractFurnaceBlockEntity furnace, String machine,
                                       MachineOpRequest request) {
        ItemStack source = player.getInventory().getItem(request.playerSlot());
        if (source.isEmpty()) {
            return failure(machine, request, "the player slot is empty");
        }
        if (!AbstractFurnaceBlockEntity.getFuel().containsKey(source.getItem())) {
            return failure(machine, request, "that item is not a fuel");
        }
        int moved = moveIntoSlot(furnace, SLOT_FUEL, source, source.getCount());
        if (moved <= 0) {
            return failure(machine, request, "the fuel slot is full");
        }
        source.shrink(moved);
        player.getInventory().setChanged();
        return new MachineOpResult(machine, request.operation(), itemId(source), moved, "inserted");
    }

    private MachineOpResult insertInput(ServerPlayer player, ServerLevel level, AbstractFurnaceBlockEntity furnace,
                                        String machine, MachineOpRequest request) {
        ItemStack source = player.getInventory().getItem(request.playerSlot());
        if (source.isEmpty()) {
            return failure(machine, request, "the player slot is empty");
        }
        boolean smeltable = level.getRecipeManager()
                .getRecipeFor(RecipeType.SMELTING, new SimpleContainer(source), level)
                .isPresent();
        if (!smeltable) {
            return failure(machine, request, "that item has no smelting recipe");
        }
        int moved = moveIntoSlot(furnace, SLOT_INPUT, source, source.getCount());
        if (moved <= 0) {
            return failure(machine, request, "the input slot is full");
        }
        source.shrink(moved);
        player.getInventory().setChanged();
        return new MachineOpResult(machine, request.operation(), itemId(source), moved, "inserted");
    }

    private MachineOpResult takeOutput(ServerPlayer player, AbstractFurnaceBlockEntity furnace, String machine,
                                       MachineOpRequest request) {
        ItemStack result = furnace.getItem(SLOT_RESULT);
        if (result.isEmpty()) {
            return failure(machine, request, "there is nothing to take");
        }
        int moved = addToMainInventory(player.getInventory(), result, result.getCount());
        if (moved <= 0) {
            return failure(machine, request, "the player's inventory is full");
        }
        result.shrink(moved);
        furnace.setChanged();
        player.getInventory().setChanged();
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

    private static int addToMainInventory(Inventory inventory, ItemStack source, int wanted) {
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

    private ServerPlayer player(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            return null;
        }
        try {
            return server.getPlayerList().getPlayer(UUID.fromString(playerId.trim()));
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    private static String itemId(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "" : id.toString();
    }

    private static MachineOpResult failure(String machine, MachineOpRequest request, String message) {
        return new MachineOpResult(machine, request.operation(), "", 0, message);
    }
}