package com.rheinmetal.tianshu.client.host.game;

/**
 * What the crosshair is on.
 *
 * <p>{@code present} distinguishes "no entity under the crosshair" from "an entity the platform could not name":
 * the two are different answers when a module asks who the player is talking to.</p>
 */
public record GameTarget(
        String entityId,
        String entityTypeId,
        String displayName,
        double distance,
        boolean present
) {
    public static final GameTarget EMPTY = new GameTarget("", "", "", 0.0D, false);

    public GameTarget {
        entityId = clean(entityId);
        entityTypeId = clean(entityTypeId);
        displayName = clean(displayName);
        distance = Math.max(0.0D, distance);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}