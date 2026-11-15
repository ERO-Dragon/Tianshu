package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.game.BlockPlacePort;
import com.rheinmetal.tianshu.api.game.BlockPlaceRequest;
import com.rheinmetal.tianshu.api.game.BlockPlaceResult;
import com.rheinmetal.tianshu.mc.body.ActionContext;
import com.rheinmetal.tianshu.mc.body.ActionPlayerFactory;
import com.rheinmetal.tianshu.mc.body.BodyRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.CompletableFuture;

/**
 * The 1.21.1 placement: finds the item in the actor's inventory and runs vanilla {@link BlockItem#useOn}, so
 * orientation, waterlogging, block entities and item consumption are the game's own.
 *
 * <p>A body borrows an {@link ActionContext}: the facade carries the body's position, rotation and inventory, and
 * disappears when the call ends. Placement is refused before that facade is built when the actor has no inventory
 * or the item is missing.</p>
 */
public final class BlockPlaceSource implements BlockPlacePort {

    private static final double REACH_SQUARED = 20.25;
    private static final int MAIN_SLOTS = 36;

    private final MinecraftServer server;
    private final BodyRegistry bodies;
    private final ActionPlayerFactory actionPlayers;

    public BlockPlaceSource(MinecraftServer server, BodyRegistry bodies, ActionPlayerFactory actionPlayers) {
        this.server = server;
        this.bodies = bodies;
        this.actionPlayers = actionPlayers;
    }

    @Override
    public CompletableFuture<BlockPlaceResult> place(ActorRef actor, BlockPlaceRequest request) {
        CompletableFuture<BlockPlaceResult> result = new CompletableFuture<>();
        server.execute(() -> result.complete(placeNow(actor, request)));
        return result;
    }

    private BlockPlaceResult placeNow(ActorRef actor, BlockPlaceRequest request) {
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
        if (distanceSquared(holder.position(), pos) > REACH_SQUARED) {
            return failure("the block is out of reach");
        }
        int slot = findSlot(inventory, request.itemId());
        if (slot < 0) {
            return failure("the actor has no " + request.itemId());
        }
        if (!(inventory.getItem(slot).getItem() instanceof BlockItem)) {
            return failure("that item cannot be placed");
        }
        Direction face = Faces.of(request.face(), holder.position(), pos);
        BlockPos relative = pos.relative(face);
        BlockState beforeHere = holder.level().getBlockState(pos);
        BlockState beforeThere = holder.level().getBlockState(relative);

        ActionContext context = ActionContext.open(server, actor, bodies, actionPlayers).orElse(null);
        if (context == null) {
            return failure(actor.kind() == ActorRef.Kind.BODY
                    ? "the agent body is not present"
                    : "the player is not online");
        }
        try {
            ItemStack working = context.inventory().getItem(slot);
            if (!(working.getItem() instanceof BlockItem blockItem)) {
                return failure("that item cannot be placed");
            }
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos).relative(face, 0.5D), face, pos, false);
            BlockPlaceContext placeContext = new BlockPlaceContext(context.player(), InteractionHand.MAIN_HAND,
                    working, hit);
            InteractionResult outcome = blockItem.useOn(placeContext);
            context.commit();
            if (!outcome.consumesAction()) {
                return failure("the placement had no effect");
            }
            BlockPos placed = locate(blockItem, holder, pos, relative, beforeHere, beforeThere);
            if (placed == null) {
                return failure("the item was used but no block appeared at the target");
            }
            BlockState state = holder.level().getBlockState(placed);
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            return new BlockPlaceResult(id == null ? "" : id.toString(),
                    placed.getX(), placed.getY(), placed.getZ(), true, "placed");
        } finally {
            context.close();
        }
    }

    private static BlockPos locate(BlockItem blockItem, ToolActor holder, BlockPos pos, BlockPos relative,
                                   BlockState beforeHere, BlockState beforeThere) {
        BlockState nowThere = holder.level().getBlockState(relative);
        if (nowThere.getBlock() == blockItem.getBlock() && nowThere != beforeThere) {
            return relative;
        }
        BlockState nowHere = holder.level().getBlockState(pos);
        if (nowHere.getBlock() == blockItem.getBlock() && nowHere != beforeHere) {
            return pos;
        }
        return null;
    }

    private static int findSlot(Container inventory, String itemId) {
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id != null && id.toString().equals(itemId)) {
                return slot;
            }
        }
        return -1;
    }

    private static double distanceSquared(Vec3 from, BlockPos pos) {
        double dx = from.x - (pos.getX() + 0.5);
        double dy = from.y - (pos.getY() + 0.5);
        double dz = from.z - (pos.getZ() + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }

    private static BlockPlaceResult failure(String message) {
        return new BlockPlaceResult("", 0, 0, 0, false, message);
    }
}
