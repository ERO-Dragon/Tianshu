package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.game.ContainerTransferPort;
import com.rheinmetal.tianshu.api.game.ContainerTransferRequest;
import com.rheinmetal.tianshu.api.game.ContainerTransferResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.CompletableFuture;

/**
 * The 1.21.1 container transfer: one container slot and one actor slot, as the requesting actor.
 *
 * <p>Moves are computed against both inventories before anything is written, and the amount moved is what actually
 * fit. A mismatched target slot is refused instead of overwritten. A player and an agent body differ only in which
 * 36-slot store sits behind {@code actorSlot}.</p>
 */
public final class ContainerTransferSource implements ContainerTransferPort {

    private static final double MAX_DISTANCE_SQUARED = 36.0;
    private static final int MAIN_INVENTORY_SIZE = 36;

    private final MinecraftServer server;

    public ContainerTransferSource(MinecraftServer server) {
        this.server = server;
    }

    @Override
    @Deprecated
    public CompletableFuture<ContainerTransferResult> transfer(String playerId, ContainerTransferRequest request) {
        return transfer(ActorRef.player(playerId, false), request);
    }

    @Override
    public CompletableFuture<ContainerTransferResult> transfer(ActorRef actor, ContainerTransferRequest request) {
        CompletableFuture<ContainerTransferResult> result = new CompletableFuture<>();
        server.execute(() -> result.complete(transferNow(actor, request)));
        return result;
    }

    private ContainerTransferResult transferNow(ActorRef actor, ContainerTransferRequest request) {
        ToolActor holder = ToolActors.resolve(server, actor).orElse(null);
        if (holder == null) {
            return failure(actor.kind() == ActorRef.Kind.BODY
                    ? "the agent body is not present"
                    : "the player is not online");
        }
        Container inventory = holder.inventory();
        if (inventory == null) {
            return failure("that agent body has no inventory");
        }
        BlockPos pos = new BlockPos(request.x(), request.y(), request.z());
        if (!holder.level().hasChunkAt(pos)) {
            return failure("that chunk is not loaded");
        }
        if (distanceSquared(holder.position(), pos) > MAX_DISTANCE_SQUARED) {
            return failure("the container is out of reach");
        }
        BlockEntity blockEntity = holder.level().getBlockEntity(pos);
        if (!(blockEntity instanceof Container container)) {
            return failure("that block is not a container");
        }
        if (request.containerSlot() < 0 || request.containerSlot() >= container.getContainerSize()
                || request.playerSlot() < 0 || request.playerSlot() >= MAIN_INVENTORY_SIZE) {
            return failure("that slot does not exist");
        }
        return request.toPlayer()
                ? containerToActor(inventory, container, request)
                : actorToContainer(inventory, container, request);
    }

    private static ContainerTransferResult containerToActor(Container inventory, Container container,
                                                            ContainerTransferRequest request) {
        ItemStack source = container.getItem(request.containerSlot());
        if (source.isEmpty()) {
            return failure("the container slot is empty");
        }
        ItemStack moving = source.copy();
        moving.setCount(Math.min(request.count(), source.getCount()));
        int moved = Inventories.add(inventory, moving, MAIN_INVENTORY_SIZE);
        if (moved <= 0) {
            return failure("the actor's inventory is full");
        }
        source.shrink(moved);
        container.setChanged();
        return success(moving, moved);
    }

    private static ContainerTransferResult actorToContainer(Container inventory, Container container,
                                                            ContainerTransferRequest request) {
        ItemStack source = inventory.getItem(request.playerSlot());
        if (source.isEmpty()) {
            return failure("the actor slot is empty");
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
        return success(source, moved);
    }

    private static double distanceSquared(Vec3 from, BlockPos pos) {
        double dx = from.x - (pos.getX() + 0.5);
        double dy = from.y - (pos.getY() + 0.5);
        double dz = from.z - (pos.getZ() + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }

    private static ContainerTransferResult success(ItemStack stack, int moved) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return new ContainerTransferResult(id == null ? "" : id.toString(), moved, "moved");
    }

    private static ContainerTransferResult failure(String message) {
        return new ContainerTransferResult("", 0, message);
    }
}