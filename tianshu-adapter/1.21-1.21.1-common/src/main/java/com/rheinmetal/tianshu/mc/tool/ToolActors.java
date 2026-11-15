package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.mc.body.BodyRegistry;
import com.rheinmetal.tianshu.mc.body.BodyRuntime;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.UUID;

/** Turns an {@link ActorRef} into the state a tool needs, without either path guessing about the other. */
public final class ToolActors {

    private static final int OPERATOR_PERMISSION_LEVEL = 2;

    private ToolActors() {
    }

    public static Optional<ToolActor> resolve(MinecraftServer server, ActorRef actor) {
        return switch (actor.kind()) {
            case PLAYER -> Optional.ofNullable(player(server, actor.id())).map(PlayerActor::new);
            case BODY -> body(actor.id()).map(BodyActor::new);
        };
    }

    private static ServerPlayer player(MinecraftServer server, String playerId) {
        if (playerId == null || playerId.isBlank()) {
            return null;
        }
        try {
            return server.getPlayerList().getPlayer(UUID.fromString(playerId.trim()));
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    private static Optional<BodyRegistry.BodyState> body(String bodyId) {
        return BodyRuntime.current().flatMap(registry -> registry.state(bodyId));
    }

    private record PlayerActor(ServerPlayer player) implements ToolActor {

        @Override
        public ServerLevel level() {
            return player.serverLevel();
        }

        @Override
        public Vec3 position() {
            return player.position();
        }

        @Override
        public Container inventory() {
            return player.getInventory();
        }

        @Override
        public boolean operator() {
            return player.hasPermissions(OPERATOR_PERMISSION_LEVEL);
        }

        @Override
        public void drop(ItemStack stack) {
            player.drop(stack, false);
        }
    }

    private record BodyActor(BodyRegistry.BodyState state) implements ToolActor {

        @Override
        public ServerLevel level() {
            return state.level();
        }

        @Override
        public Vec3 position() {
            return state.position();
        }

        @Override
        public Container inventory() {
            return state.inventory();
        }

        @Override
        public boolean operator() {
            return state.operator();
        }

        @Override
        public void drop(ItemStack stack) {
            Vec3 at = state.position();
            state.level().addFreshEntity(new ItemEntity(state.level(), at.x, at.y + 0.5, at.z, stack));
        }
    }
}