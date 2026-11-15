package com.rheinmetal.tianshu.api.agent;

import java.util.Objects;

/** One non-air block seen by a scan. */
public record BlockHit(String blockId, String dimension, int x, int y, int z) {

    public BlockHit {
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(dimension, "dimension");
    }
}