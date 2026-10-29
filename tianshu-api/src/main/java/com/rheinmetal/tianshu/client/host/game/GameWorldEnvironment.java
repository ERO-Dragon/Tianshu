package com.rheinmetal.tianshu.client.host.game;

/** Weather, time of day and biome, sampled for the groups that report them. */
public record GameWorldEnvironment(
        boolean raining,
        boolean thundering,
        long dayTime,
        String biomeId,
        String biomeDisplayName
) {
    public static final GameWorldEnvironment EMPTY = new GameWorldEnvironment(false, false, 0L, "", "");

    public GameWorldEnvironment {
        biomeId = clean(biomeId);
        biomeDisplayName = clean(biomeDisplayName);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}