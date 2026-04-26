package com.rheinmetal.tianshu.snapshot;

public final class DeathContextData {

    public final String damageSourceId;
    public final String deathMessage;
    public final double x;
    public final double y;
    public final double z;
    public final String dimension;
    public final String killerEntityId;

    public DeathContextData(
            String damageSourceId,
            String deathMessage,
            double x,
            double y,
            double z,
            String dimension,
            String killerEntityId
    ) {
        this.damageSourceId = damageSourceId;
        this.deathMessage = deathMessage;
        this.x = x;
        this.y = y;
        this.z = z;
        this.dimension = dimension;
        this.killerEntityId = killerEntityId;
    }

    public String getDamageSourceId() { return damageSourceId; }
    public String getDeathMessage() { return deathMessage; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public String getDimension() { return dimension; }
    public String getKillerEntityId() { return killerEntityId; }
}
