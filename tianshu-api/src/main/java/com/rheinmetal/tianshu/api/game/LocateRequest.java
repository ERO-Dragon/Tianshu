package com.rheinmetal.tianshu.api.game;

/**
 * What to look for, and how far.
 *
 * <p>Exactly one of {@code structure} and {@code biome} is expected: they are two different questions ("where is the
 * nearest village" and "where is the nearest desert"), and a request that names both has no single answer. The
 * implementation refuses such a request rather than picking one.</p>
 *
 * <p>The radius is in blocks, because that is what a caller reasons about; structure search converts it to chunks
 * internally.</p>
 */
public record LocateRequest(String structure, String biome, int radiusBlocks) {

    public LocateRequest {
        structure = structure == null ? "" : structure.trim();
        biome = biome == null ? "" : biome.trim();
    }

    /** True when exactly one target was named. */
    public boolean singleTarget() {
        return structure.isEmpty() != biome.isEmpty();
    }
}