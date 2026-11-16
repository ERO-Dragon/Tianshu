package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.game.BlockInteractPort;
import com.rheinmetal.tianshu.api.game.BlockInteractRequest;
import com.rheinmetal.tianshu.api.game.BlockInteractResult;
import com.rheinmetal.tianshu.mc.body.ActionContext;
import com.rheinmetal.tianshu.mc.body.ActionPlayerFactory;
import com.rheinmetal.tianshu.mc.body.BodyRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The 1.21.1 block interaction: performs the same {@code use} the player would, on the server thread.
 *
 * <p>Only harmless blocks are accepted. The switch is the kind of block, not a coordinate or a whitelist of
 * positions, so a door at any position is a door and a furnace is never a door. Range and chunk-load checks keep a
 * call from reaching across dimensions or into unloaded terrain.</p>
 *
 * <p>A body borrows an {@link ActionContext} for the call, because the vanilla use path takes a player; the facade
 * carries the body's position and inventory and is discarded afterwards.</p>
 */
public final class BlockInteractSource implements BlockInteractPort {

    private static final double MAX_DISTANCE_SQUARED = 36.0;

    private final MinecraftServer server;
    private final BodyRegistry bodies;
    private final ActionPlayerFactory actionPlayers;

    public BlockInteractSource(MinecraftServer server, BodyRegistry bodies, ActionPlayerFactory actionPlayers) {
        this.server = server;
        this.bodies = bodies;
        this.actionPlayers = actionPlayers;
    }

    @Override
    public CompletableFuture<BlockInteractResult> interact(String playerId, BlockInteractRequest request) {
        CompletableFuture<BlockInteractResult> result = new CompletableFuture<>();
        server.execute(() -> result.complete(interactNow(playerId, request)));
        return result;
    }

    @Override
    public CompletableFuture<BlockInteractResult> interact(ActorRef actor, BlockInteractRequest request) {
        if (actor.kind() == ActorRef.Kind.PLAYER) {
            return interact(actor.id(), request);
        }
        CompletableFuture<BlockInteractResult> result = new CompletableFuture<>();
        server.execute(() -> result.complete(interactAsBody(actor, request)));
        return result;
    }

    private BlockInteractResult interactNow(String playerId, BlockInteractRequest request) {
        ServerPlayer player = player(playerId);
        if (player == null) {
            return new BlockInteractResult("", false, "the player is not online");
        }
        ServerLevel level = player.serverLevel();
        BlockPos pos = new BlockPos(request.x(), request.y(), request.z());
        BlockInteractResult refused = check(level, pos, player.position(), player.distanceToSqr(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
        if (refused != null) {
            return refused;
        }
        BlockState state = level.getBlockState(pos);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        InteractionResult outcome = state.useWithoutItem(level, player, hit);
        return result(state, outcome);
    }

    private BlockInteractResult interactAsBody(ActorRef actor, BlockInteractRequest request) {
        BodyRegistry.BodyState body = bodies.state(actor.id()).orElse(null);
        if (body == null) {
            return new BlockInteractResult("", false, "the agent body is not present");
        }
        ServerLevel level = body.level();
        BlockPos pos = new BlockPos(request.x(), request.y(), request.z());
        BlockInteractResult refused = check(level, pos, body.position(),
                body.position().distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
        if (refused != null) {
            return refused;
        }
        BlockState state = level.getBlockState(pos);
        Direction face = Faces.of(null, body.position(), pos);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos).relative(face, 0.5D), face, pos, false);
        ActionContext context = ActionContext.open(server, actor, bodies).orElse(null);
        if (context == null) {
            return new BlockInteractResult("", false, "the agent body is not present");
        }
        try {
            InteractionResult outcome = state.useWithoutItem(level, context.player(), hit);
            context.commit();
            return result(state, outcome);
        } finally {
            context.close();
        }
    }

    /** Chunk, range and harmlessness in one place, so both actor paths share the same rules. */
    private static BlockInteractResult check(ServerLevel level, BlockPos pos, Vec3 from, double distanceSquared) {
        if (!level.hasChunkAt(pos)) {
            return new BlockInteractResult("", false, "that chunk is not loaded");
        }
        if (distanceSquared > MAX_DISTANCE_SQUARED) {
            return new BlockInteractResult("", false, "the block is out of reach");
        }
        Block block = level.getBlockState(pos).getBlock();
        if (!isHarmless(block)) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            return new BlockInteractResult(id == null ? "" : id.toString(), false,
                    "only buttons, levers, doors, trapdoors and fence gates may be operated");
        }
        return null;
    }

    private static BlockInteractResult result(BlockState state, InteractionResult outcome) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return new BlockInteractResult(id == null ? "" : id.toString(), outcome.consumesAction(),
                outcome.consumesAction() ? "operated" : "the block did not react");
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

    private static boolean isHarmless(Block block) {
        return block instanceof ButtonBlock
                || block instanceof LeverBlock
                || block instanceof DoorBlock
                || block instanceof TrapDoorBlock
                || block instanceof FenceGateBlock;
    }
}