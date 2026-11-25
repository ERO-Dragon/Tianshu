package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.game.ContainerTransferPort;
import com.rheinmetal.tianshu.api.game.ContainerTransferRequest;
import com.rheinmetal.tianshu.api.game.ContainerTransferResult;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.CompletableFuture;

/**
 * The 1.21.1 container transfer: one block-side slot and one actor slot, as the requesting actor.
 *
 * <p>Both sides are {@link ItemStore}s. That is what makes a vanilla chest and a modded machine's item handler the same
 * operation: before the store existed, the block side was read through {@code Container} only, so a machine exposing
 * nothing but a loader item handler could not be moved into at all — it was refused as "not a container".</p>
 *
 * <p>Slot numbers are the ones {@code container.inspect} reports, because both sides resolve the block through
 * {@link ItemStores}: two resolutions that disagreed would let a caller move items into a slot it was never shown.</p>
 *
 * <p>The amount moved is what actually fit, and a slot holding a different item is refused rather than overwritten.</p>
 */
public final class ContainerTransferSource implements ContainerTransferPort {

    private static final double MAX_DISTANCE_SQUARED = 36.0;
    private static final int MAIN_INVENTORY_SIZE = 36;

    private final MinecraftServer server;
    private final BlockStorageProbe probe;

    public ContainerTransferSource(MinecraftServer server, BlockStorageProbe probe) {
        this.server = server;
        this.probe = probe == null ? BlockStorageProbe.NONE : probe;
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
        ItemStore block = ItemStores.of(holder.level(), pos, probe);
        if (block.size() <= 0) {
            return failure("that block is not a container");
        }
        if (request.containerSlot() < 0 || request.containerSlot() >= block.size()
                || request.playerSlot() < 0 || request.playerSlot() >= MAIN_INVENTORY_SIZE) {
            return failure("that slot does not exist");
        }
        ItemStore actorStore = new ContainerStore(inventory);
        return request.toPlayer()
                ? blockToActor(actorStore, block, request)
                : actorToBlock(actorStore, block, request);
    }

    private static ContainerTransferResult blockToActor(ItemStore actor, ItemStore block,
                                                        ContainerTransferRequest request) {
        ItemStack source = block.stackIn(request.containerSlot());
        if (source.isEmpty()) {
            return failure("the container slot is empty");
        }
        ItemStack moving = source.copy();
        moving.setCount(Math.min(request.count(), source.getCount()));
        int moved = actor.insert(request.playerSlot(), moving);
        if (moved <= 0) {
            return failure("the actor slot cannot accept that item");
        }
        block.extract(request.containerSlot(), moved);
        block.changed();
        actor.changed();
        return success(moving, moved);
    }

    private static ContainerTransferResult actorToBlock(ItemStore actor, ItemStore block,
                                                        ContainerTransferRequest request) {
        ItemStack source = actor.stackIn(request.playerSlot());
        if (source.isEmpty()) {
            return failure("the actor slot is empty");
        }
        ItemStack target = block.stackIn(request.containerSlot());
        if (!target.isEmpty() && !ItemStack.isSameItemSameComponents(target, source)) {
            return failure("the container slot holds a different item");
        }
        ItemStack moving = source.copy();
        moving.setCount(Math.min(request.count(), source.getCount()));
        int moved = block.insert(request.containerSlot(), moving);
        if (moved <= 0) {
            return failure("the container slot is full");
        }
        actor.extract(request.playerSlot(), moved);
        actor.changed();
        block.changed();
        return success(moving, moved);
    }

    private static double distanceSquared(Vec3 from, BlockPos pos) {
        double dx = from.x - (pos.getX() + 0.5);
        double dy = from.y - (pos.getY() + 0.5);
        double dz = from.z - (pos.getZ() + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }

    private static ContainerTransferResult success(ItemStack stack, int moved) {
        String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return new ContainerTransferResult(itemId, moved, "moved " + moved);
    }

    private static ContainerTransferResult failure(String message) {
        return new ContainerTransferResult("", 0, message);
    }
}