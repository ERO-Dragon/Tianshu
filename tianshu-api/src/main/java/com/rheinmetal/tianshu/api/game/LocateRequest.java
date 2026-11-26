package com.rheinmetal.tianshu.api.game;

/**
 * What to look for, and how far.
 *
 * <p>Exactly one of {@code structure} and {@code biome} is expected: they are two different questions ("where is the
 * nearest village" and "where is the nearest desert"), and a request that names both has no single answer. The
 * implementation refuses such a request rather than picking one.</p>
 *
 * <p>The radius is horizontal, in blocks. Scope determines whether the search can consult world generation.</p>
 */
public record LocateRequest(String structure, String biome, int radiusBlocks, Scope scope) {

    public enum Scope { GENERATED, WORLD }

    public static final int DEFAULT_RADIUS_BLOCKS = 256;
    public static final int MAX_RADIUS_BLOCKS = 512;

    public boolean validRadius() {
        return radiusBlocks > 0 && radiusBlocks <= MAX_RADIUS_BLOCKS;
    }

    public LocateRequest {
        java.util.Objects.requireNonNull(scope, "scope");
        structure = structure == null ? "" : structure.trim();
        biome = biome == null ? "" : biome.trim();
    }

    /** True when exactly one target was named. */
    public boolean singleTarget() {
        return structure.isEmpty() != biome.isEmpty();
    }
}