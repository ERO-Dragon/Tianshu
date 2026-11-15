package com.rheinmetal.tianshu.api.agent;

import java.util.Objects;

/** A point in one dimension, named the way the platform names positions. */
public record BlockTarget(String dimension, int x, int y, int z) {

    public BlockTarget {
        Objects.requireNonNull(dimension, "dimension");
    }
}