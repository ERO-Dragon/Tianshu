package com.rheinmetal.tianshu.mc.tool;

import com.mojang.serialization.Codec;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/** Read live full chunks or their saved data. Neither path requests a chunk ticket or runs world generation. */
final class GeneratedChunkSearch {
    private GeneratedChunkSearch() {}

    static WorldLocateSource.Search structure(ServerLevel level, BlockPos from, Structure structure,
                                              ResourceLocation id, int radius) {
        return new LocateGridSearch(from.getX(), 0, from.getZ(), 0, 0, radius, "structure", id.toString(),
                (x, z) -> probe(level, new ChunkPos(x >> 4, z >> 4), chunk -> {
                    var start = chunk.getStartForStructure(structure);
                    return start != null && start.isValid();
                }, tag -> {
                    var start = tag.getCompound("structures").getCompound("starts").getCompound(id.toString());
                    return id.toString().equals(start.getString("id"))
                            && !start.getList("Children", Tag.TAG_COMPOUND).isEmpty();
                }));
    }

    static WorldLocateSource.Search biome(ServerLevel level, BlockPos from, ResourceKey<Biome> target, int radius) {
        var registry = level.registryAccess().registryOrThrow(Registries.BIOME);
        var plains = registry.getHolderOrThrow(Biomes.PLAINS);
        Codec<PalettedContainerRO<Holder<Biome>>> codec = PalettedContainer.codecRO(registry.asHolderIdMap(),
                registry.holderByNameCodec(), PalettedContainer.Strategy.SECTION_BIOMES, plains);
        int y = Mth.clamp(from.getY(), level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
        int quartY = QuartPos.fromBlock(y);
        return new LocateGridSearch(from.getX(), from.getY(), from.getZ(), from.getX(), from.getZ(), radius, "biome",
                target.location().toString(), (x, z) -> {
                    int quartX = QuartPos.fromBlock(x);
                    int quartZ = QuartPos.fromBlock(z);
                    return probe(level, new ChunkPos(x >> 4, z >> 4),
                            chunk -> chunk.getNoiseBiome(quartX, quartY, quartZ).is(target), tag -> {
                                for (Tag sectionTag : tag.getList("sections", Tag.TAG_COMPOUND)) {
                                    CompoundTag section = (CompoundTag) sectionTag;
                                    if (section.getByte("Y") == SectionPos.blockToSectionCoord(y)
                                            && section.contains("biomes", Tag.TAG_COMPOUND)) {
                                        var biomes = codec.parse(NbtOps.INSTANCE, section.getCompound("biomes")).getOrThrow();
                                        return biomes.get(quartX & 3, quartY & 3, quartZ & 3).is(target);
                                    }
                                }
                                // The vanilla chunk reader initializes an absent section's biome palette to plains.
                                return plains.is(target);
                            });
                });
    }

    private static CompletableFuture<LocateGridSearch.Match> probe(ServerLevel level, ChunkPos pos,
                                                                    Predicate<LevelChunk> live,
                                                                    Predicate<CompoundTag> saved) {
        var source = level.getChunkSource();
        LevelChunk chunk = source.getChunkNow(pos.x, pos.z);
        if (chunk != null) {
            return CompletableFuture.completedFuture(match(live.test(chunk)));
        }
        var dimension = level.dimension();
        var overworld = level.getServer().overworld();
        var generator = source.getGenerator().getTypeNameForDataFixer();
        return source.chunkMap.read(pos).thenApplyAsync(optional -> {
            if (optional.isEmpty()) {
                return LocateGridSearch.Match.ABSENT;
            }
            CompoundTag tag = source.chunkMap.upgradeChunkTag(dimension, overworld::getDataStorage, optional.get(), generator);
            if (ChunkStatus.byName(tag.getString("Status")) != ChunkStatus.FULL) {
                return LocateGridSearch.Match.ABSENT;
            }
            return match(saved.test(tag));
        }, Util.backgroundExecutor());
    }

    private static LocateGridSearch.Match match(boolean found) {
        return found ? LocateGridSearch.Match.HIT : LocateGridSearch.Match.MISS;
    }
}
