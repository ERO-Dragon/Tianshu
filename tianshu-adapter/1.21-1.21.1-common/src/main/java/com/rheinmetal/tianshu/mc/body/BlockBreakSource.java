package com.rheinmetal.tianshu.mc.body;

import com.rheinmetal.tianshu.api.entity.ActionOutcome;
import com.rheinmetal.tianshu.api.entity.ActionResult;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.BlockTarget;
import com.rheinmetal.tianshu.api.entity.EntityActionPort;
import com.rheinmetal.tianshu.api.game.BlockBreakPort;
import com.rheinmetal.tianshu.api.game.BlockBreakRequest;
import com.rheinmetal.tianshu.api.game.BlockBreakResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.CompletableFuture;

/**
 * The 1.21.1 break: one block, as the actor, through the path that actor already has.
 *
 * <p>Placing, interacting, crafting and moving items were all available to a player and to a body; breaking was only
 * available to a body ({@code body.mine}). That asymmetry meant a module acting for a player could build but not clear,
 * which is exactly backwards for the work players actually want done for them.</p>
 */
public final class BlockBreakSource implements BlockBreakPort {

    private static final double MAX_DISTANCE_SQUARED = 36.0;

    private final MinecraftServer server;
    private final BodyRegistry bodies;
    private final EntityActionPort actions;

    public BlockBreakSource(MinecraftServer server, BodyRegistry bodies, EntityActionPort actions) {
        this.server = server;
        this.bodies = bodies;
        this.actions = actions;
    }

    @Override
    public CompletableFuture<BlockBreakResult> breakBlock(ActorRef actor, BlockBreakRequest request) {
        CompletableFuture<BlockBreakResult> result = new CompletableFuture<>();
        server.execute(() -> breakNow(actor, request).whenComplete((value, failure) -> {
            if (failure != null) {
                result.complete(BlockBreakResult.failure("the break failed: " + failure.getMessage()));
                return;
            }
            result.complete(value);
        }));
        return result;
    }

    private CompletableFuture<BlockBreakResult> breakNow(ActorRef actor, BlockBreakRequest request) {
        BlockPos pos = new BlockPos(request.x(), request.y(), request.z());
        if (actor.kind() == ActorRef.Kind.BODY) {
            // A carrier mines on its own schedule and with its own hand, so its existing action path is the one that
            // knows whether it can, not a second implementation here.
            BodyRegistry.BodyState body = bodies.state(actor.id()).orElse(null);
            if (body == null) {
                return CompletableFuture.completedFuture(BlockBreakResult.failure("the agent body is not present"));
            }
            BlockTarget target = new BlockTarget(body.level().dimension().location().toString(),
                    pos.getX(), pos.getY(), pos.getZ());
            return actions.mine(actor, target).thenApply(result -> toResult(body.level().getBlockState(pos), result));
        }
        ServerPlayer player = server.getPlayerList().getPlayer(parse(actor.id()));
        if (player == null) {
            return CompletableFuture.completedFuture(BlockBreakResult.failure("the player is not online"));
        }
        if (!player.serverLevel().hasChunkAt(pos)) {
            return CompletableFuture.completedFuture(BlockBreakResult.failure("that chunk is not loaded"));
        }
        if (player.position().distanceToSqr(Vec3.atCenterOf(pos)) > MAX_DISTANCE_SQUARED) {
            return CompletableFuture.completedFuture(BlockBreakResult.failure("the block is out of reach"));
        }
        BlockState state = player.serverLevel().getBlockState(pos);
        if (state.isAir()) {
            return CompletableFuture.completedFuture(BlockBreakResult.failure("there is no block there"));
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        // Drops follow the player's own rules: a creative player leaves nothing behind, a survival player leaves what the
        // block should leave.
        boolean drops = !player.isCreative();
        boolean broken = player.serverLevel().destroyBlock(pos, drops, player);
        return CompletableFuture.completedFuture(new BlockBreakResult(
                id == null ? "" : id.toString(),
                broken,
                broken ? "broken" : "the block refused to break"));
    }

    private static BlockBreakResult toResult(BlockState state, ActionResult result) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return new BlockBreakResult(
                id == null ? "" : id.toString(),
                result.outcome() == ActionOutcome.DONE,
                result.outcome() + (result.detail().isBlank() ? "" : ": " + result.detail()));
    }

    private static java.util.UUID parse(String id) {
        try {
            return java.util.UUID.fromString(id);
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }
}