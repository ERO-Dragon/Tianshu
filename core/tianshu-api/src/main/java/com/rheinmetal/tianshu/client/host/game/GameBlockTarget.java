package com.rheinmetal.tianshu.client.host.game;

/**
 * The block under the crosshair.
 *
 * <p>{@code present} distinguishes "nothing under the crosshair" from "a block the platform could not name"; the
 * latter is a real block with an empty id. Positions are block coordinates.</p>
 */
public record GameBlockTarget(
        String blockId,
        String blockDisplayName,
        int x,
        int y,
        int z,
        boolean present
) {
    public static final GameBlockTarget EMPTY = new GameBlockTarget("", "", 0, 0, 0, false);

    public GameBlockTarget {
        blockId = clean(blockId);
        blockDisplayName = clean(blockDisplayName);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
