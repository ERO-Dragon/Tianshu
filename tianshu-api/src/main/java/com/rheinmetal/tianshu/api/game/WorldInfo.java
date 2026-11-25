package com.rheinmetal.tianshu.api.game;

/**
 * The world as it is right now, around one position.
 *
 * <p>Time, weather and difficulty are world facts; biome and light are facts about a position, which is why the request
 * carries one. The two are reported together because a decision about "is it safe to work here" needs both halves at
 * the same moment.</p>
 */
public record WorldInfo(
        String dimension,
        String dimensionType,
        String biome,
        long dayTime,
        int dayNumber,
        boolean daylight,
        String weather,
        String difficulty,
        int lightLevel,
        int playerCount,
        String message) {

    public WorldInfo {
        dimension = dimension == null ? "" : dimension;
        dimensionType = dimensionType == null ? "" : dimensionType;
        biome = biome == null ? "" : biome;
        weather = weather == null ? "" : weather;
        difficulty = difficulty == null ? "" : difficulty;
        message = message == null ? "" : message;
    }

    /** A refusal: nothing was read, and the message says why. */
    public static WorldInfo failure(String message) {
        return new WorldInfo("", "", "", 0L, 0, false, "", "", 0, 0, message);
    }

    /** True when the world itself was read. */
    public boolean read() {
        return !dimension.isBlank();
    }

    /** True when monsters can spawn where this was read: night, dark, and not peaceful. */
    public boolean hostileSpawnRisk() {
        return !daylight && lightLevel < 8 && !"peaceful".equals(difficulty);
    }
}