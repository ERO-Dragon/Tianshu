package com.rheinmetal.tianshu.snapshot;

public final class NavigationInfo {

    public final PositionData current;
    public final PositionData lastDeathPoint;
    public final PositionData spawnPoint;

    public NavigationInfo(PositionData current, PositionData lastDeathPoint, PositionData spawnPoint) {
        this.current = current;
        this.lastDeathPoint = lastDeathPoint;
        this.spawnPoint = spawnPoint;
    }

    public PositionData getCurrent() { return current; }
    public PositionData getLastDeathPoint() { return lastDeathPoint; }
    public PositionData getSpawnPoint() { return spawnPoint; }
}
