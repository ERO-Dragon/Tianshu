package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.game.LocateRequest;
import com.rheinmetal.tianshu.api.game.LocateResult;
import com.rheinmetal.tianshu.api.game.WorldLocatePort;
import com.rheinmetal.tianshu.api.tool.CancellationSignal;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Owns the server-wide locate budget and retires all searches when their world session ends. */
public final class WorldLocateSource implements WorldLocatePort, AutoCloseable {
    private static final int MAX_SEARCHES = 8;
    private static final int SAMPLES_PER_TICK = 128;

    interface Search {
        /** Advances on the server thread. A null result means more ticks are needed. */
        LocateResult advance(int budget);
    }

    private final MinecraftServer server;
    private final Set<Job> active = new HashSet<>();
    private boolean closed;

    private static final class Job {
        final ActorRef actor;
        final LocateRequest request;
        final CancellationSignal cancellation;
        final CompletableFuture<LocateResult> result = new CompletableFuture<>();
        Search search;

        Job(ActorRef actor, LocateRequest request, CancellationSignal cancellation) {
            this.actor = actor;
            this.request = request;
            this.cancellation = cancellation;
        }

        boolean cancelled() {
            return result.isDone() || cancellation.isCancelled();
        }
    }

    public WorldLocateSource(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public synchronized CompletableFuture<LocateResult> locate(ActorRef actor, LocateRequest request,
                                                                CancellationSignal cancellation) {
        if (!request.singleTarget() || !request.validRadius()) {
            return CompletableFuture.completedFuture(LocateResult.failure(
                    "Expected one structure or biome and a radius of 1 to " + LocateRequest.MAX_RADIUS_BLOCKS));
        }
        if (closed || active.size() >= MAX_SEARCHES) {
            return CompletableFuture.failedFuture(closed
                    ? new ToolException(ToolError.UNAVAILABLE, "Server session closed")
                    : new ToolException(ToolError.BUSY, "World locate is busy"));
        }
        Job job = new Job(actor, request, cancellation);
        active.add(job);
        return job.result.orTimeout(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }

    private synchronized List<Job> jobs() {
        return List.copyOf(active);
    }

    public void tick() {
        List<Job> jobs = jobs();
        for (Job job : jobs) {
            try {
                if (job.cancelled()) {
                    job.result.cancel(false);
                } else {
                    if (job.search == null) {
                        job.search = start(job);
                    }
                    LocateResult result = job.search.advance(SAMPLES_PER_TICK / jobs.size());
                    if (result != null) {
                        job.result.complete(result);
                    }
                }
            } catch (RuntimeException failure) {
                job.result.completeExceptionally(failure);
            }
            if (job.result.isDone()) {
                synchronized (this) {
                    active.remove(job);
                }
            }
        }
    }

    private Search start(Job job) {
        ToolActor actor = ToolActors.resolve(server, job.actor).orElseThrow(() ->
                new ToolException(ToolError.UNAVAILABLE, "Locate origin is no longer present"));
        LocateRequest request = job.request;
        boolean biome = request.structure().isEmpty();
        String target = biome ? request.biome() : request.structure();
        ResourceLocation id = ResourceLocation.tryParse(target);
        if (id != null) {
            BlockPos from = BlockPos.containing(actor.position());
            if (biome) {
                var key = ResourceKey.create(Registries.BIOME, id);
                if (server.registryAccess().registryOrThrow(Registries.BIOME).containsKey(id)) {
                    if (request.scope() == LocateRequest.Scope.GENERATED) {
                        return GeneratedChunkSearch.biome(actor.level(), from, key, request.radiusBlocks());
                    }
                    return biomeSearch(actor.level(), from, key, request.radiusBlocks());
                }
            } else {
                var structure = server.registryAccess().registryOrThrow(Registries.STRUCTURE)
                        .getHolder(ResourceKey.create(Registries.STRUCTURE, id));
                if (structure.isPresent()) {
                    if (request.scope() == LocateRequest.Scope.GENERATED) {
                        return GeneratedChunkSearch.structure(actor.level(), from, structure.get().value(), id,
                                request.radiusBlocks());
                    }
                    return new StructureLocateSearch(actor.level(), from, structure.get(), id, request.radiusBlocks(),
                            job::cancelled);
                }
            }
        }
        return budget -> LocateResult.failure("Unknown " + (biome ? "biome" : "structure") + ": " + target);
    }

    private static Search biomeSearch(ServerLevel level, BlockPos from, ResourceKey<Biome> target, int radius) {
        // Use the vanilla biome blend with the generator as a fallback, without acquiring chunk tickets.
        BiomeManager biomes = level.getBiomeManager().withDifferentSource((x, y, z) -> {
            var loaded = level.getChunkSource().getChunkNow(QuartPos.toSection(x), QuartPos.toSection(z));
            return loaded == null ? level.getUncachedNoiseBiome(x, y, z) : loaded.getNoiseBiome(x, y, z);
        });
        int y = from.getY();
        return new LocateGridSearch(from.getX(), y, from.getZ(), from.getX(), from.getZ(), radius, "biome",
                target.location().toString(), (x, z) -> CompletableFuture.completedFuture(
                        biomes.getBiome(new BlockPos(x, y, z)).is(target) ? LocateGridSearch.Match.HIT : LocateGridSearch.Match.MISS));
    }

    @Override
    public void close() {
        List<Job> pending;
        synchronized (this) {
            closed = true;
            pending = List.copyOf(active);
            active.clear();
        }
        pending.forEach(job -> job.result.completeExceptionally(
                new ToolException(ToolError.UNAVAILABLE, "Server session closed")));
    }
}
