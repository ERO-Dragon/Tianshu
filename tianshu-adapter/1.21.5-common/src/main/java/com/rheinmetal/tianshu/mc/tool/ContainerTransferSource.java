package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.game.ContainerTransferPort;
import com.rheinmetal.tianshu.api.game.ContainerTransferRequest;
import com.rheinmetal.tianshu.api.game.ContainerTransferResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The 1.21.1 container transfer: one container slot and one player slot, as the requesting player.
 *
 * <p>Moves are computed against both inventories before anything is written, and the amount moved is what actually
 * fit. A mismatched target slot is refused instead of overwritten.</p>
 */
public final class ContainerTransferSource implements ContainerTransferPort {

    private static final double MAX_DISTANCE_SQUARED = 36.0;
    private static final int MAIN_INVENTORY_SIZE = 36;

    private final MinecraftServer server;

    public ContainerTransferSource(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public CompletableFuture<ContainerTransferResult> transfer(String playerId, ContainerTransferRequest request) {
        CompletableFuture<ContainerTransferResult> result = new CompletableFuture<>();
        server.execute(() -> result.complete(transferNow(playerId, request)));
        return result;
    }

    private ContainerTransferResult transferNow(String playerId, ContainerTransferRequest request) {
        ServerPlayer player = player(playerId);
        if (player == null) {
            return failure("the player is not online");
        }
        ServerLevel level = (ServerLevel) player.level();
        BlockPos pos = new BlockPos(request.x(), request.y(), request.z());
        if (!level.hasChunkAt(pos)) {
            return failure("that chunk is not loaded");
        }
        if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > MAX_DISTANCE_SQUARED) {
            return failure("the container is out of reach");
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof Container container)) {
            return failure("that block is not a container");
        }
        if (request.containerSlot() < 0 || request.containerSlot() >= container.getContainerSize()
                || request.playerSlot() < 0 || request.playerSlot() >= MAIN_INVENTORY_SIZE) {
            return failure("that slot does not exist");
        }
        return request.toPlayer()
                ? containerToPlayer(player, container, request)
                : playerToContainer(player, container, request);
    }

    private static ContainerTransferResult containerToPlayer(ServerPlayer player, Container container,
                                                             ContainerTransferRequest request) {
        ItemStack source = container.getItem(request.containerSlot());
        if (source.isEmpty()) {
            return failure("the container slot is empty");
        }
        int wanted = Math.min(request.count(), source.getCount());
        int moved = addToMainInventory(player.getInventory(), source, wanted);
        if (moved <= 0) {
            return failure("the player's inventory is full");
        }
        source.shrink(moved);
        container.setChanged();
        return success(player, source, moved);
    }

    private static ContainerTransferResult playerToContainer(ServerPlayer player, Container container,
                                                             ContainerTransferRequest request) {
        Inventory inventory = player.getInventory();
        ItemStack source = inventory.getItem(request.playerSlot());
        if (source.isEmpty()) {
            return failure("the player slot is empty");
        }
        ItemStack target = container.getItem(request.containerSlot());
        if (!target.isEmpty() && !ItemStack.isSameItemSameComponents(target, source)) {
            return failure("the container slot holds a different item");
        }
        int room = target.isEmpty() ? source.getMaxStackSize() : source.getMaxStackSize() - target.getCount();
        int moved = Math.min(Math.min(request.count(), source.getCount()), room);
        if (moved <= 0) {
            return failure("the container slot is full");
        }
        if (target.isEmpty()) {
            ItemStack placed = source.copy();
            placed.setCount(moved);
            container.setItem(request.containerSlot(), placed);
        } else {
            target.grow(moved);
        }
        source.shrink(moved);
        container.setChanged();
        inventory.setChanged();
        return success(player, source, moved);
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
        inventory.setChanged();
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

    private static ContainerTransferResult success(ServerPlayer player, ItemStack source, int moved) {
        player.getInventory().setChanged();
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(source.getItem());
        return new ContainerTransferResult(id == null ? "" : id.toString(), moved, "moved");
    }

    private static ContainerTransferResult failure(String message) {
        return new ContainerTransferResult("", 0, message);
    }
}