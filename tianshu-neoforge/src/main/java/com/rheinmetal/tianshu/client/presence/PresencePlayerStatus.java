package com.rheinmetal.tianshu.client.presence;

public record PresencePlayerStatus(
        float health,
        float maxHealth,
        int hunger,
        int experienceLevel
) {
    public PresencePlayerStatus {
        health = Math.max(0.0F, health);
        maxHealth = Math.max(0.0F, maxHealth);
        hunger = Math.max(0, hunger);
        experienceLevel = Math.max(0, experienceLevel);
    }

    public static PresencePlayerStatus empty() {
        return new PresencePlayerStatus(0.0F, 0.0F, 0, 0);
    }
}
