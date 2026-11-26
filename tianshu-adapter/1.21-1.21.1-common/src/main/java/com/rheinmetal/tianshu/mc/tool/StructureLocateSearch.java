package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.game.LocateResult;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/** Enumerate placement candidates with worldgen state, then verify actual starts in distance order. */
final class StructureLocateSearch implements WorldLocateSource.Search {
    private record Candidate(ChunkPos chunk, BlockPos at, long distanceSquared) {}

    private final ServerLevel level;
    private final Structure target;
    private final String id;
    private final int radius;
    private final CompletableFuture<List<Candidate>> candidates;
    private final BooleanSupplier cancelled;
    private CompletableFuture<ChunkResult<ChunkAccess>> loading;
    private int index;

    StructureLocateSearch(ServerLevel level, BlockPos from, Holder<Structure> structure, ResourceLocation id,
                          int radius, BooleanSupplier cancelled) {
        this.level = level;
        this.target = structure.value();
        this.id = id.toString();
        this.radius = radius;
        this.cancelled = cancelled;
        var state = level.getChunkSource().getGeneratorState();
        var placements = List.copyOf(state.getPlacementsForStructure(structure));
        candidates = CompletableFuture.supplyAsync(() -> candidates(from, radius, state, placements, cancelled),
                Util.backgroundExecutor());
    }

    private static List<Candidate> candidates(BlockPos origin, int radius, ChunkGeneratorStructureState state,
                                             List<StructurePlacement> placements, BooleanSupplier cancelled) {
        List<Candidate> found = new ArrayList<>();
        for (StructurePlacement placement : placements) {
            // Locate offsets need not be at the chunk origin. Translate the search bounds into this placement's grid.
            BlockPos offset = placement.getLocatePos(ChunkPos.ZERO);
            int minX = SectionPos.blockToSectionCoord(origin.getX() - radius - offset.getX());
            int maxX = SectionPos.blockToSectionCoord(origin.getX() + radius - offset.getX());
            int minZ = SectionPos.blockToSectionCoord(origin.getZ() - radius - offset.getZ());
            int maxZ = SectionPos.blockToSectionCoord(origin.getZ() + radius - offset.getZ());
            for (int x = minX; x <= maxX; x++) {
                if (cancelled.getAsBoolean()) {
                    return List.of();
                }
                for (int z = minZ; z <= maxZ; z++) {
                    ChunkPos chunk = new ChunkPos(x, z);
                    BlockPos at = placement.getLocatePos(chunk);
                    long dx = (long) at.getX() - origin.getX();
                    long dz = (long) at.getZ() - origin.getZ();
                    long squared = dx * dx + dz * dz;
                    if (squared <= (long) radius * radius && placement.isStructureChunk(state, x, z)) {
                        found.add(new Candidate(chunk, at, squared));
                    }
                }
            }
        }
        found.sort(Comparator.comparingLong(Candidate::distanceSquared));
        return found;
    }

    @Override
    public LocateResult advance(int budget) {
        List<Candidate> ready = candidates.getNow(null);
        if (ready == null) {
            return null;
        }
        while (index < ready.size() && budget-- > 0) {
            Candidate candidate = ready.get(index);
            ChunkPos pos = candidate.chunk();
            ChunkAccess chunk;
            if (loading == null) {
                chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
                if (chunk == null) {
                    var cache = level.getChunkSource();
                    // Vanilla's off-thread entry enqueues the request on its main-thread processor and returns a
                    // future. Its server-thread overload waits for generation, which would stall this tick.
                    loading = CompletableFuture.supplyAsync(() -> {
                        if (cancelled.getAsBoolean()) {
                            throw new java.util.concurrent.CancellationException();
                        }
                        return cache.getChunkFuture(pos.x, pos.z, ChunkStatus.STRUCTURE_STARTS, true);
                    }, Util.backgroundExecutor()).thenCompose(future -> future);
                    return null;
                }
            } else {
                ChunkResult<ChunkAccess> result = loading.getNow(null);
                if (result == null) {
                    return null;
                }
                chunk = result.orElseThrow(() -> new IllegalStateException(result.getError()));
                loading = null;
            }
            index++;
            var start = chunk.getStartForStructure(target);
            if (start != null && start.isValid()) {
                BlockPos at = candidate.at();
                return new LocateResult("structure", id, true, at.getX(), at.getY(), at.getZ(),
                        Math.sqrt(candidate.distanceSquared()), 0, "found");
            }
        }
        return index == ready.size()
                ? LocateResult.notFound("structure", id, 0, "No matching start within " + radius + " blocks")
                : null;
    }
}
