package com.rheinmetal.tianshu.api.agent;

import java.util.Objects;

/** How a move should be performed and how close is close enough. */
public record MoveOptions(MoveMode mode, double arriveRadius) {

    public static final MoveOptions DEFAULT = new MoveOptions(MoveMode.ANY, 0.5);

    public MoveOptions {
        Objects.requireNonNull(mode, "mode");
        if (!Double.isFinite(arriveRadius) || arriveRadius < 0) {
            throw new IllegalArgumentException("arriveRadius must be finite and non-negative: " + arriveRadius);
        }
    }
}