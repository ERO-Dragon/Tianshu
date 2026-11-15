package com.rheinmetal.tianshu.api.game;

import java.util.Objects;

/** What got placed, where, or why nothing did. {@code placed=false} carries the reason in {@code message}. */
public record BlockPlaceResult(String blockId, int x, int y, int z, boolean placed, String message) {

    public BlockPlaceResult {
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(message, "message");
    }
}