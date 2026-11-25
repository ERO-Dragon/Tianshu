package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.game.ActorStatus;
import com.rheinmetal.tianshu.api.game.ActorStatusPort;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.concurrent.CompletableFuture;

/**
 * The 1.21.1 actor status read: position, dimension, health, hunger, air, fire, water.
 *
 * <p>Read from the living entity rather than from the body registry's cached position: a carrier that walked since the
 * last tick has a stale cached position, and a status read that reports where the body was is worse than no read.</p>
 */
public final class ActorStatusSource implements ActorStatusPort {

    private final MinecraftServer server;

    public ActorStatusSource(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public CompletableFuture<ActorStatus> status(ActorRef actor) {
        CompletableFuture<ActorStatus> result = new CompletableFuture<>();
        server.execute(() -> result.complete(statusNow(actor)));
        return result;
    }

    private ActorStatus statusNow(ActorRef actor) {
        ToolActor holder = ToolActors.resolve(server, actor).orElse(null);
        if (holder == null) {
            return ActorStatus.failure(actor.kind() == ActorRef.Kind.BODY
                    ? "the agent body is not present"
                    : "the player is not online");
        }
        LivingEntity living = holder.living();
        if (living == null) {
            return ActorStatus.failure("that agent body is no longer in the world");
        }
        ResourceLocation dimension = holder.level().dimension().location();
        return new ActorStatus(
                actor.id(),
                actor.kind().name(),
                dimension == null ? "" : dimension.toString(),
                living.getX(), living.getY(), living.getZ(),
                living.getYRot(), living.getXRot(),
                living.getHealth(), living.getMaxHealth(),
                hungerOf(living), saturationOf(living),
                living.getAirSupply(),
                living.isOnFire(), living.isInWater(),
                living.isAlive(),
                gameModeOf(living),
                "read");
    }

    /** Hunger is a player concept; a carrier that is not a player reports full, which is what "no hunger" means. */
    private static int hungerOf(LivingEntity living) {
        return living instanceof ServerPlayer player ? player.getFoodData().getFoodLevel() : 20;
    }

    private static float saturationOf(LivingEntity living) {
        return living instanceof ServerPlayer player ? player.getFoodData().getSaturationLevel() : 0.0F;
    }

    private static String gameModeOf(LivingEntity living) {
        return living instanceof ServerPlayer player ? player.gameMode.getGameModeForPlayer().getName() : "";
    }
}