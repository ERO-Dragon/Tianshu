package com.rheinmetal.tianshu.api.entity;

import java.util.Objects;

/** An entity to act on, named by its server-side id. */
public record EntityTarget(String id) {

    public EntityTarget {
        Objects.requireNonNull(id, "id");
    }
}