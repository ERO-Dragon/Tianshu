package com.rheinmetal.tianshu.api.game;

/**
 * A block's energy buffer, as one pair of numbers.
 *
 * <p>{@link #EMPTY} is the honest answer for "this block has no energy storage": a capacity of zero cannot be confused
 * with a buffer that happens to be drained, which is what an empty optional would have to be explained away as.</p>
 */
public record EnergyView(long stored, long capacity) {

    /** No energy storage at all. */
    public static final EnergyView EMPTY = new EnergyView(0L, 0L);

    public EnergyView {
        if (stored < 0L || capacity < 0L || stored > Math.max(capacity, 0L)) {
            throw new IllegalArgumentException("energy must satisfy 0 <= stored <= capacity");
        }
    }

    /** True when the block actually stores energy; a drained buffer is still a storage. */
    public boolean present() {
        return capacity > 0L;
    }
}