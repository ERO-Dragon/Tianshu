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

    public final boolean occlusionVisible;
    public final float boundingHeight;
    public final float eyeHeight;

    public final String mainHandItemId;
    public final float attackDamage;
    public final float armorValue;
    public final String detailText;
    public final MrEntityExplanationData entityExplanationData;

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
            boolean occlusionVisible,
            float boundingHeight,
            float eyeHeight,
            String mainHandItemId,
            float attackDamage,
            float armorValue
    ) {
        this(entityId, uuid, targetUuid, displayName, relativeX, relativeY, relativeZ, horizontalAngle, distance, hostile, health, maxHealth, motionX, motionY, motionZ, pullingBow, sneaking, occlusionVisible, boundingHeight, eyeHeight, mainHandItemId, attackDamage, armorValue, null, null);
    }

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
            boolean occlusionVisible,
            float boundingHeight,
            float eyeHeight,
            String mainHandItemId,
            float attackDamage,
            float armorValue,
            String detailText,
            MrEntityExplanationData entityExplanationData
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
        this.occlusionVisible = occlusionVisible;
        this.boundingHeight = boundingHeight;
        this.eyeHeight = eyeHeight;
        this.mainHandItemId = mainHandItemId;
        this.attackDamage = attackDamage;
        this.armorValue = armorValue;
        this.detailText = detailText;
        this.entityExplanationData = entityExplanationData;
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
    public boolean isOcclusionVisible() { return occlusionVisible; }
    public float getBoundingHeight() { return boundingHeight; }
    public float getEyeHeight() { return eyeHeight; }
    public String getMainHandItemId() { return mainHandItemId; }
    public float getAttackDamage() { return attackDamage; }
    public float getArmorValue() { return armorValue; }
    public String getDetailText() { return detailText; }
    public MrEntityExplanationData getEntityExplanationData() { return entityExplanationData; }

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
