package com.rheinmetal.tianshu.api.agent;

import java.util.Objects;

/** A position in one dimension, without any Minecraft type. */
public record ActorPosition(String dimension, double x, double y, double z) {

    public ActorPosition {
        Objects.requireNonNull(dimension, "dimension");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("position must be finite");
        }
    }
}
