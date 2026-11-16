package com.rheinmetal.tianshu.api.entity;

import java.util.Objects;

/**
 * What to look at: a radius around the actor, a cap on results, and an exact-id filter per kind.
 *
 * <p>A blank filter means "do not scan that kind" — there is no implicit "everything" mode, so a call always says
 * what it wants and never floods the conversation with a full cube of blocks.</p>
 */
public record ScanRequest(int radius, int limit, String blockFilter, String entityFilter) {

    public ScanRequest {
        if (radius <= 0) {
            throw new IllegalArgumentException("radius must be positive: " + radius);
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        Objects.requireNonNull(blockFilter, "blockFilter");
        Objects.requireNonNull(entityFilter, "entityFilter");
    }

    public boolean wantsBlocks() {
        return !blockFilter.isBlank();
    }

    public boolean wantsEntities() {
        return !entityFilter.isBlank();
    }
}