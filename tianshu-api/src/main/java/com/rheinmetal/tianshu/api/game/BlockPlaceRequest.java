package com.rheinmetal.tianshu.api.game;

import com.rheinmetal.tianshu.api.agent.BlockFace;

import java.util.Objects;

/** Place one item into the world at a position; {@code face} may be null to let the carrier aim itself. */
public record BlockPlaceRequest(int x, int y, int z, String itemId, BlockFace face) {

    public BlockPlaceRequest {
        Objects.requireNonNull(itemId, "itemId");
        if (itemId.isBlank()) {
            throw new IllegalArgumentException("itemId must not be blank");
        }
    }
}