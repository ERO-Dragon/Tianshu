package com.rheinmetal.tianshu.snapshot;

public final class WaypointData {

    public final String name;
    public final double x;
    public final double y;
    public final double z;
    public final String dimension;
    public final String sourceMod;

    public WaypointData(
            String name,
            double x,
            double y,
            double z,
            String dimension,
            String sourceMod
    ) {
        this.name = name;
        this.x = x;
        this.y = y;
        this.z = z;
        this.dimension = dimension;
        this.sourceMod = sourceMod;
    }

    public String getName() { return name; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public String getDimension() { return dimension; }
    public String getSourceMod() { return sourceMod; }
}
