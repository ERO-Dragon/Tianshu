package com.rheinmetal.tianshu.api.entity;

import java.util.List;
import java.util.Objects;

/** What a scan found, in one dimension. */
public record ScanResult(List<BlockHit> blocks, List<EntityHit> entities) {

    public ScanResult {
        Objects.requireNonNull(blocks, "blocks");
        Objects.requireNonNull(entities, "entities");
    }
}