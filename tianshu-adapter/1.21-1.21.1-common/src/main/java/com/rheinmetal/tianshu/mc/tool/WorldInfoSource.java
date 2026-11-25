package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.game.WorldInfo;
import com.rheinmetal.tianshu.api.game.WorldInfoPort;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.concurrent.CompletableFuture;

/**
 * The 1.21.1 world read: dimension, biome, time, weather, difficulty, light around the actor.
 *
 * <p>Biome and light are read at the actor's own position — the block it stands on — because that is the position the
 * caller is asking about when it asks "is it safe here". Time and weather come from the server: they are world facts,
 * not per-player ones.</p>
 */
public final class WorldInfoSource implements WorldInfoPort {

    private final MinecraftServer server;

    public WorldInfoSource(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public CompletableFuture<WorldInfo> info(ActorRef actor) {
        CompletableFuture<WorldInfo> result = new CompletableFuture<>();
        server.execute(() -> result.complete(infoNow(actor)));
        return result;
    }

    private WorldInfo infoNow(ActorRef actor) {
        ToolActor holder = ToolActors.resolve(server, actor).orElse(null);
        if (holder == null) {
            return WorldInfo.failure(actor.kind() == ActorRef.Kind.BODY
                    ? "the agent body is not present"
                    : "the player is not online");
        }
        ServerLevel level = holder.level();
        BlockPos pos = BlockPos.containing(holder.position());
        ResourceLocation dimension = level.dimension().location();
        // The type key, not the dimension key: two datapack dimensions can share the overworld type, and it is the type
        // that decides sky, bed behaviour and whether it can rain.
        ResourceLocation dimensionType = level.dimensionTypeRegistration().unwrapKey()
                .map(key -> key.location())
                .orElse(null);
        ResourceLocation biome = level.getBiome(pos).unwrapKey()
                .map(key -> key.location())
                .orElse(null);
        return new WorldInfo(
                dimension == null ? "" : dimension.toString(),
                dimensionType == null ? "" : dimensionType.toString(),
                biome == null ? "" : biome.toString(),
                level.getDayTime(),
                (int) (level.getDayTime() / 24000L),
                level.isDay(),
                weatherOf(level),
                server.getWorldData().getDifficulty().getKey(),
                level.getMaxLocalRawBrightness(pos),
                server.getPlayerCount(),
                "read");
    }

    private static String weatherOf(ServerLevel level) {
        if (level.isThundering()) {
            return "thunder";
        }
        return level.isRaining() ? "rain" : "clear";
    }
}