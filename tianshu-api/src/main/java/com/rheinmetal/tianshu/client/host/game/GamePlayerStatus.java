package com.rheinmetal.tianshu.client.host.game;

/** Health, hunger and level, sampled for the groups that report them. */
public record GamePlayerStatus(
        float health,
        float maxHealth,
        int hunger,
        int experienceLevel
) {
    public static final GamePlayerStatus EMPTY = new GamePlayerStatus(0.0F, 0.0F, 0, 0);

    public GamePlayerStatus {
        health = Math.max(0.0F, health);
        maxHealth = Math.max(0.0F, maxHealth);
        hunger = Math.max(0, hunger);
        experienceLevel = Math.max(0, experienceLevel);
    }
}