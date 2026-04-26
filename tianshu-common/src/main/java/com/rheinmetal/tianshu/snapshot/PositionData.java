package com.rheinmetal.tianshu.snapshot;

public final class PositionData {

    public final double x;
    public final double y;
    public final double z;
    public final float yaw;
    public final float pitch;
    public final String dimension;
    public final String playerUuid;

    public PositionData(double x, double y, double z, float yaw, float pitch, String dimension, String playerUuid) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.dimension = dimension;
        this.playerUuid = playerUuid;
    }

    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public float getYaw() { return yaw; }
    public float getPitch() { return pitch; }
    public String getDimension() { return dimension; }
    public String getPlayerUuid() { return playerUuid; }

    public double distanceTo(PositionData other) {
        if (other == null) return -1;
        double dx = this.x - other.x;
        double dy = this.y - other.y;
        double dz = this.z - other.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public double horizontalAngleTo(PositionData target) {
        if (target == null) return 0;
        double dx = target.x - this.x;
        double dz = target.z - this.z;
        double targetAngle = Math.toDegrees(Math.atan2(-dx, dz));
        double diff = targetAngle - this.yaw;
        while (diff > 180) diff -= 360;
        while (diff < -180) diff += 360;
        return diff;
    }
}
