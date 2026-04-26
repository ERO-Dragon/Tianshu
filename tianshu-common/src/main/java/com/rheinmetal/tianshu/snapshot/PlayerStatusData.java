package com.rheinmetal.tianshu.snapshot;

public final class PlayerStatusData {

    public final float health;
    public final float maxHealth;
    public final int hunger;
    public final float saturation;
    public final int experienceLevel;
    public final String gameMode;
    public final long lastDamageGameTick;
    public final String lastDamageSourceId;
    public final int airSupply;
    public final int maxAirSupply;

    public PlayerStatusData(
            float health,
            float maxHealth,
            int hunger,
            float saturation,
            int experienceLevel,
            String gameMode,
            long lastDamageGameTick,
            String lastDamageSourceId,
            int airSupply,
            int maxAirSupply
    ) {
        this.health = health;
        this.maxHealth = maxHealth;
        this.hunger = hunger;
        this.saturation = saturation;
        this.experienceLevel = experienceLevel;
        this.gameMode = gameMode;
        this.lastDamageGameTick = lastDamageGameTick;
        this.lastDamageSourceId = lastDamageSourceId;
        this.airSupply = airSupply;
        this.maxAirSupply = maxAirSupply;
    }

    public float getHealth() { return health; }
    public float getMaxHealth() { return maxHealth; }
    public int getHunger() { return hunger; }
    public float getSaturation() { return saturation; }
    public int getExperienceLevel() { return experienceLevel; }
    public String getGameMode() { return gameMode; }
    public long getLastDamageGameTick() { return lastDamageGameTick; }
    public String getLastDamageSourceId() { return lastDamageSourceId; }
    public int getAirSupply() { return airSupply; }
    public int getMaxAirSupply() { return maxAirSupply; }
}
