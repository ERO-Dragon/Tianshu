package com.rheinmetal.tianshu.api.entity;

import java.util.Objects;

/** One entity seen by a scan. */
public record EntityHit(String entityId, String type, String dimension, double x, double y, double z) {

    public EntityHit {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(dimension, "dimension");
    }
}