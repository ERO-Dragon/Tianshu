package com.rheinmetal.tianshu.snapshot;

public final class WorldEnvironmentData {

    public final boolean raining;
    public final boolean thundering;
    public final long dayTimeTicks;
    public final long totalTicks;
    public final String biomeId;

    public WorldEnvironmentData(
            boolean raining,
            boolean thundering,
            long dayTimeTicks,
            long totalTicks,
            String biomeId
    ) {
        this.raining = raining;
        this.thundering = thundering;
        this.dayTimeTicks = dayTimeTicks;
        this.totalTicks = totalTicks;
        this.biomeId = biomeId;
    }

    public boolean isRaining() { return raining; }
    public boolean isThundering() { return thundering; }
    public long getDayTimeTicks() { return dayTimeTicks; }
    public long getTotalTicks() { return totalTicks; }
    public String getBiomeId() { return biomeId; }
}
