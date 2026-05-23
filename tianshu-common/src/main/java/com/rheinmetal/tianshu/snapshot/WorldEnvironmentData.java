package com.rheinmetal.tianshu.snapshot;

public final class WorldEnvironmentData {

    public final boolean raining;
    public final boolean thundering;
    public final long dayTimeTicks;
    public final String biomeId;
    public final String biomeDisplayName;

    public WorldEnvironmentData(
            boolean raining,
            boolean thundering,
            long dayTimeTicks,
            String biomeId,
            String biomeDisplayName
    ) {
        this.raining = raining;
        this.thundering = thundering;
        this.dayTimeTicks = dayTimeTicks;
        this.biomeId = biomeId;
        this.biomeDisplayName = biomeDisplayName;
    }

    public boolean isRaining() { return raining; }
    public boolean isThundering() { return thundering; }
    public long getDayTimeTicks() { return dayTimeTicks; }
    public String getBiomeId() { return biomeId; }
    public String getBiomeDisplayName() { return biomeDisplayName; }
}
