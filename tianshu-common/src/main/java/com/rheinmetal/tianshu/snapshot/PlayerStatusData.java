package com.rheinmetal.tianshu.snapshot;

public final class PlayerStatusData {

    public final float health;
    public final float maxHealth;
    public final int hunger;
    public final int experienceLevel;

    public PlayerStatusData(
            float health,
            float maxHealth,
            int hunger,
            int experienceLevel
    ) {
        this.health = health;
        this.maxHealth = maxHealth;
        this.hunger = hunger;
        this.experienceLevel = experienceLevel;
    }

    public float getHealth() { return health; }
    public float getMaxHealth() { return maxHealth; }
    public int getHunger() { return hunger; }
    public int getExperienceLevel() { return experienceLevel; }
}
