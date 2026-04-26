package com.rheinmetal.tianshu.snapshot;

public final class WorldEnvironmentData {

    public final boolean raining;
    public final boolean thundering;
    public final long dayTimeTicks;
    public final long totalTicks;
    public final String biomeId;
    public final String biomeDisplayName;
    public final float secondsUntilNight;
    public final float secondsUntilDay;
    public final float skyLight;
    public final int moonPhase;
    public final String difficulty;
    public final boolean isHardcore;

    public WorldEnvironmentData(
            boolean raining,
            boolean thundering,
            long dayTimeTicks,
            long totalTicks,
            String biomeId,
            String biomeDisplayName,
            float secondsUntilNight,
            float secondsUntilDay,
            float skyLight,
            int moonPhase,
            String difficulty,
            boolean isHardcore
    ) {
        this.raining = raining;
        this.thundering = thundering;
        this.dayTimeTicks = dayTimeTicks;
        this.totalTicks = totalTicks;
        this.biomeId = biomeId;
        this.biomeDisplayName = biomeDisplayName;
        this.secondsUntilNight = secondsUntilNight;
        this.secondsUntilDay = secondsUntilDay;
        this.skyLight = skyLight;
        this.moonPhase = moonPhase;
        this.difficulty = difficulty;
        this.isHardcore = isHardcore;
    }

    public boolean isRaining() { return raining; }
    public boolean isThundering() { return thundering; }
    public long getDayTimeTicks() { return dayTimeTicks; }
    public long getTotalTicks() { return totalTicks; }
    public String getBiomeId() { return biomeId; }
    public String getBiomeDisplayName() { return biomeDisplayName; }
    public float getSecondsUntilNight() { return secondsUntilNight; }
    public float getSecondsUntilDay() { return secondsUntilDay; }
    public float getSkyLight() { return skyLight; }
    public int getMoonPhase() { return moonPhase; }
    public String getDifficulty() { return difficulty; }
    public boolean isHardcore() { return isHardcore; }
}
