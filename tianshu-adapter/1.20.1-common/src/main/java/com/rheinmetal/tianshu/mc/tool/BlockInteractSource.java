package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.game.BlockInteractPort;
import com.rheinmetal.tianshu.api.game.BlockInteractRequest;
import com.rheinmetal.tianshu.api.game.BlockInteractResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
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

/** The 1.20.1 block interaction: the same use the player would perform, on the server thread. */
public final class BlockInteractSource implements BlockInteractPort {

    private static final double MAX_DISTANCE_SQUARED = 36.0;

    private final MinecraftServer server;

    public BlockInteractSource(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public CompletableFuture<BlockInteractResult> interact(String playerId, BlockInteractRequest request) {
        CompletableFuture<BlockInteractResult> result = new CompletableFuture<>();
        server.execute(() -> result.complete(interactNow(playerId, request)));
        return result;
    }

    private BlockInteractResult interactNow(String playerId, BlockInteractRequest request) {
        ServerPlayer player = player(playerId);
        if (player == null) {
            return new BlockInteractResult("", false, "the player is not online");
        }
        ServerLevel level = player.serverLevel();
        BlockPos pos = new BlockPos(request.x(), request.y(), request.z());
        if (!level.hasChunkAt(pos)) {
            return new BlockInteractResult("", false, "that chunk is not loaded");
        }
        if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > MAX_DISTANCE_SQUARED) {
            return new BlockInteractResult("", false, "the block is out of reach");
        }
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        if (!isHarmless(block)) {
            return new BlockInteractResult(id == null ? "" : id.toString(), false,
                    "only buttons, levers, doors, trapdoors and fence gates may be operated");
        }
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        InteractionResult outcome = state.use(level, player, InteractionHand.MAIN_HAND, hit);
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
