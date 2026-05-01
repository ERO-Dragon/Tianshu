package com.rheinmetal.tianshu.snapshot;

import java.util.Objects;

public final class NearbyEntityData {

    public final String entityId;
    public final String uuid;
    public final String targetUuid;

    public final String displayName;

    public final double relativeX;
    public final double relativeY;
    public final double relativeZ;
    public final double horizontalAngle;
    public final double distance;
    public final boolean hostile;

    public final float health;
    public final float maxHealth;

    public final double motionX;
    public final double motionY;
    public final double motionZ;

    public final boolean pullingBow;
    public final boolean sneaking;

    public final boolean lineOfSight;
    public final float boundingHeight;

    public final String mainHandItemId;
    public final float attackDamage;
    public final float armorValue;

    public NearbyEntityData(
            String entityId,
            String uuid,
            String targetUuid,
            String displayName,
            double relativeX,
            double relativeY,
            double relativeZ,
            double horizontalAngle,
            double distance,
            boolean hostile,
            float health,
            float maxHealth,
            double motionX,
            double motionY,
            double motionZ,
            boolean pullingBow,
            boolean sneaking,
            boolean lineOfSight,
            float boundingHeight,
            String mainHandItemId,
            float attackDamage,
            float armorValue
    ) {
        this.entityId = entityId;
        this.uuid = uuid;
        this.targetUuid = targetUuid;
        this.displayName = displayName;
        this.relativeX = relativeX;
        this.relativeY = relativeY;
        this.relativeZ = relativeZ;
        this.horizontalAngle = horizontalAngle;
        this.distance = distance;
        this.hostile = hostile;
        this.health = health;
        this.maxHealth = maxHealth;
        this.motionX = motionX;
        this.motionY = motionY;
        this.motionZ = motionZ;
        this.pullingBow = pullingBow;
        this.sneaking = sneaking;
        this.lineOfSight = lineOfSight;
        this.boundingHeight = boundingHeight;
        this.mainHandItemId = mainHandItemId;
        this.attackDamage = attackDamage;
        this.armorValue = armorValue;
    }

    public String getEntityId() { return entityId; }
    public String getUuid() { return uuid; }
    public String getTargetUuid() { return targetUuid; }
    public String getDisplayName() { return displayName; }
    public double getRelativeX() { return relativeX; }
    public double getRelativeY() { return relativeY; }
    public double getRelativeZ() { return relativeZ; }
    public double getHorizontalAngle() { return horizontalAngle; }
    public double getDistance() { return distance; }
    public boolean isHostile() { return hostile; }
    public float getHealth() { return health; }
    public float getMaxHealth() { return maxHealth; }
    public double getMotionX() { return motionX; }
    public double getMotionY() { return motionY; }
    public double getMotionZ() { return motionZ; }
    public boolean isPullingBow() { return pullingBow; }
    public boolean isSneaking() { return sneaking; }
    public boolean isLineOfSight() { return lineOfSight; }
    public float getBoundingHeight() { return boundingHeight; }
    public String getMainHandItemId() { return mainHandItemId; }
    public float getAttackDamage() { return attackDamage; }
    public float getArmorValue() { return armorValue; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NearbyEntityData that = (NearbyEntityData) o;
        return Objects.equals(entityId, that.entityId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entityId);
    }
}
