package com.rheinmetal.tianshu.function.AcousticRadar;

public final class RadarIndicator {

    public final String entityType;
    public final String displayName;
    public final double relativeAngle;
    public final double distance;

    public RadarIndicator(String entityType, String displayName, double relativeAngle, double distance) {
        this.entityType = entityType;
        this.displayName = displayName;
        this.relativeAngle = relativeAngle;
        this.distance = distance;
    }

    public String getEntityType() { return entityType; }
    public String getDisplayName() { return displayName; }
    public double getRelativeAngle() { return relativeAngle; }
    public double getDistance() { return distance; }
}
