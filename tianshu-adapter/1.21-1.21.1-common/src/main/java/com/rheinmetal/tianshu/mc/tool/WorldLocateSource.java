package com.rheinmetal.tianshu.mc.tool;

import com.mojang.datafixers.util.Pair;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.game.LocateRequest;
import com.rheinmetal.tianshu.api.game.LocateResult;
import com.rheinmetal.tianshu.api.game.WorldLocatePort;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.concurrent.CompletableFuture;

/**
 * The 1.21.1 locate: nearest structure through the chunk generator, nearest biome by bounded sampling.
 *
 * <p>The two halves are genuinely different, and the result says so.</p>
 *
 * <ul>
 *   <li><b>Structures</b> have a real search: the generator knows where its features are, so
 *       {@code findNearestMapStructure} answers exactly and cheaply for the whole radius.</li>
 *   <li><b>Biomes</b> have no such index. The climate sampler is per-version and awkward to reach from here, so this
 *       samples the biome at a grid of positions and reports how many positions that was. The answer is the nearest
 *       match <em>in the sample</em>, and a "not found" means "not in the sampled grid", not "not in the world" — which
 *       is why {@link LocateResult#sampled()} exists and why the tool description says so.</li>
 * </ul>
 *
 * <p>Structure ids are resolved through the registry, so an unknown id is a refusal with a reason instead of an empty
 * search that would look like "none nearby".</p>
 */
public final class WorldLocateSource implements WorldLocatePort {

    /** Biome sampling step, in blocks. Coarse on purpose: biomes are hundreds of blocks wide. */
    private static final int BIOME_SAMPLE_STEP = 16;
    /** Upper bound on biome samples, so a wide search cannot stall the server thread for seconds. */
    private static final int BIOME_SAMPLE_LIMIT = 4096;
    private static final int DEFAULT_RADIUS_BLOCKS = 256;

    private final MinecraftServer server;

    public WorldLocateSource(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public CompletableFuture<LocateResult> locate(ActorRef actor, LocateRequest request) {
        CompletableFuture<LocateResult> result = new CompletableFuture<>();
        server.execute(() -> result.complete(locateNow(actor, request)));
        return result;
    }

    private LocateResult locateNow(ActorRef actor, LocateRequest request) {
        if (!request.singleTarget()) {
            return LocateResult.failure("locate needs exactly one of: a structure id or a biome id");
        }
        ToolActor holder = ToolActors.resolve(server, actor).orElse(null);
        if (holder == null) {
            return LocateResult.failure(actor.kind() == ActorRef.Kind.BODY
                    ? "the agent body is not present"
                    : "the player is not online");
        }
        int radius = request.radiusBlocks() > 0 ? request.radiusBlocks() : DEFAULT_RADIUS_BLOCKS;
        try {
            return request.structure().isEmpty()
                    ? findBiome(holder, request.biome(), radius)
                    : findStructure(holder, request.structure(), radius);
        } catch (RuntimeException failure) {
            return LocateResult.failure("the search failed: " + failure.getClass().getSimpleName()
                    + ": " + failure.getMessage());
        }
    }

    private LocateResult findStructure(ToolActor holder, String structureId, int radiusBlocks) {
        ResourceLocation id = ResourceLocation.tryParse(structureId);
        if (id == null) {
            return LocateResult.failure("'" + structureId + "' is not a valid structure id");
        }
        ResourceKey<Structure> key = ResourceKey.create(Registries.STRUCTURE, id);
        Holder<Structure> target = server.registryAccess()
                .registryOrThrow(Registries.STRUCTURE)
                .getHolder(key)
                .orElse(null);
        if (target == null) {
            return LocateResult.failure("no structure is registered as " + id);
        }
        BlockPos from = BlockPos.containing(holder.position());
        int chunkRadius = Math.max(1, radiusBlocks / 16);
        // ServerLevel on purpose: Level.getChunkSource() is typed as ChunkSource, which has no generator.
        ServerLevel level = holder.level();
        Pair<BlockPos, Holder<Structure>> hit = level.getChunkSource().getGenerator()
                .findNearestMapStructure(level, HolderSet.direct(target), from, chunkRadius, false);
        if (hit == null) {
            return LocateResult.notFound("structure", id.toString(), 0,
                    "no " + id + " within " + radiusBlocks + " blocks");
        }
        BlockPos at = hit.getFirst();
        return new LocateResult("structure", id.toString(), true,
                at.getX(), at.getY(), at.getZ(), distance(from, at), 0, "found");
    }

    private LocateResult findBiome(ToolActor holder, String biomeId, int radiusBlocks) {
        ResourceLocation id = ResourceLocation.tryParse(biomeId);
        if (id == null) {
            return LocateResult.failure("'" + biomeId + "' is not a valid biome id");
        }
        ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, id);
        if (server.registryAccess().registryOrThrow(Registries.BIOME).getHolder(key).isEmpty()) {
            return LocateResult.failure("no biome is registered as " + id);
        }
        BlockPos from = BlockPos.containing(holder.position());
        ServerLevel level = holder.level();
        int sampled = 0;
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -radiusBlocks; dx <= radiusBlocks; dx += BIOME_SAMPLE_STEP) {
            for (int dz = -radiusBlocks; dz <= radiusBlocks; dz += BIOME_SAMPLE_STEP) {
                if (sampled >= BIOME_SAMPLE_LIMIT) {
                    break;
                }
                BlockPos at = new BlockPos(from.getX() + dx, from.getY(), from.getZ() + dz);
                sampled++;
                if (!level.getBiome(at).is(key)) {
                    continue;
                }
                double distance = distance(from, at);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = at;
                }
            }
        }
        if (best == null) {
            return LocateResult.notFound("biome", id.toString(), sampled,
                    "no " + id + " among " + sampled + " sampled positions within " + radiusBlocks + " blocks");
        }
        return new LocateResult("biome", id.toString(), true,
                best.getX(), best.getY(), best.getZ(), bestDistance, sampled, "found");
    }

    private static double distance(BlockPos from, BlockPos to) {
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}