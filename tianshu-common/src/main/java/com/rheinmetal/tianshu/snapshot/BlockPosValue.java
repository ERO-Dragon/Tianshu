package com.rheinmetal.tianshu.snapshot;

public final class BlockPosValue {

    public final int x;
    public final int y;
    public final int z;

    public BlockPosValue(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }

    public double distanceTo(BlockPosValue other) {
        if (other == null) return -1;
        double dx = this.x - other.x;
        double dy = this.y - other.y;
        double dz = this.z - other.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BlockPosValue that = (BlockPosValue) o;
        return x == that.x && y == that.y && z == that.z;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(x, y, z);
    }

    @Override
    public String toString() {
        return "BlockPosValue{x=" + x + ", y=" + y + ", z=" + z + "}";
    }
}
