package com.rheinmetal.tianshu.client.host.game;

/**
 * Where the local player is and how they are oriented.
 *
 * <p>{@code gameMode} is the platform's own id (for example {@code survival}); the api keeps it a string so a
 * feature module never has to know a Minecraft enum.</p>
 */
public record GamePlayerPose(
        String dimensionId,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        String gameMode
) {
    public static final GamePlayerPose EMPTY = new GamePlayerPose("", 0.0D, 0.0D, 0.0D, 0.0F, 0.0F, "");

    public GamePlayerPose {
        dimensionId = clean(dimensionId);
        gameMode = clean(gameMode);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
