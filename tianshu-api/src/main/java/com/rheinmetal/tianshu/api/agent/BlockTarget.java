package com.rheinmetal.tianshu.api.agent;

import java.util.Objects;

/**
 * A point in one dimension, named the way the platform names positions.
 *
 * <p>The coordinate range is checked here rather than in each tool: a value beyond it cannot name a block in any
 * dimension, and forwarding one turned a caller's typo (or a model's arithmetic slip, {@code x = 2e9}) into an
 * adapter-level failure with no explanation of what was wrong. One value type, one check, every tool inherits it.</p>
 *
 * <p>The bound is the vanilla world border, which is also far past anything a real session produces; a custom
 * dimension that reaches further would have to change this constant, and that is the point of it being named once.</p>
 */
public record BlockTarget(String dimension, int x, int y, int z) {

    /** The farthest a coordinate may be from the origin: the world border, in blocks. */
    public static final int MAX_COORDINATE = 30_000_000;

    public BlockTarget {
        Objects.requireNonNull(dimension, "dimension");
        requireInRange(x, "x");
        requireInRange(y, "y");
        requireInRange(z, "z");
    }

    public static void requireInRange(int coordinate, String axis) {
        if (coordinate < -MAX_COORDINATE || coordinate > MAX_COORDINATE) {
            throw new IllegalArgumentException(
                    axis + " is out of range: " + coordinate + " (allowed " + -MAX_COORDINATE + ".."
                            + MAX_COORDINATE + ")");
        }
    }
}