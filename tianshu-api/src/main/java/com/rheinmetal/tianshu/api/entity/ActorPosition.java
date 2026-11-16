package com.rheinmetal.tianshu.api.entity;

import java.util.Objects;

/**
 * A position in one dimension, without any Minecraft type.
 *
 * <p>Finite is not enough: a value beyond {@link BlockTarget#MAX_COORDINATE} cannot name a place in any dimension, and
 * passing one on turned a model's arithmetic slip into an adapter-level failure that explained nothing. The same bound
 * as {@link BlockTarget} applies, so "where can a body be" has one answer.</p>
 */
public record ActorPosition(String dimension, double x, double y, double z) {

    public ActorPosition {
        Objects.requireNonNull(dimension, "dimension");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("position must be finite");
        }
        requireInRange(x, "x");
        requireInRange(y, "y");
        requireInRange(z, "z");
    }

    private static void requireInRange(double coordinate, String axis) {
        if (Math.abs(coordinate) > BlockTarget.MAX_COORDINATE) {
            throw new IllegalArgumentException(axis + " is out of range: " + coordinate
                    + " (allowed " + -BlockTarget.MAX_COORDINATE + ".." + BlockTarget.MAX_COORDINATE + ")");
        }
    }
}