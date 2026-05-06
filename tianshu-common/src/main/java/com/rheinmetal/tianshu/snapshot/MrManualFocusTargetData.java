package com.rheinmetal.tianshu.snapshot;

public final class MrManualFocusTargetData {

    public enum TargetType { ENTITY, BLOCK }

    private final TargetType type;
    private final String uuid;
    private final String registryId;
    private final String displayName;
    private final double relativeX;
    private final double relativeY;
    private final double relativeZ;
    private final double worldX;
    private final double worldY;
    private final double worldZ;
    private final double distance;
    private final float health;
    private final float maxHealth;
    private final float attackDamage;
    private final float armorValue;
    private final String mainHandItemId;
    private final boolean hostile;
    private final boolean occlusionVisible;
    private final float boundingHeight;
    private final float eyeHeight;
    private final String detailText;

    public MrManualFocusTargetData(TargetType type, String uuid, String registryId, String displayName, double relativeX, double relativeY, double relativeZ, double worldX, double worldY, double worldZ, double distance, float health, float maxHealth, float attackDamage, float armorValue, String mainHandItemId, boolean hostile, boolean occlusionVisible, float boundingHeight, float eyeHeight, String detailText) {
        this.type = type;
        this.uuid = uuid;
        this.registryId = registryId;
        this.displayName = displayName;
        this.relativeX = relativeX;
        this.relativeY = relativeY;
        this.relativeZ = relativeZ;
        this.worldX = worldX;
        this.worldY = worldY;
        this.worldZ = worldZ;
        this.distance = distance;
        this.health = health;
        this.maxHealth = maxHealth;
        this.attackDamage = attackDamage;
        this.armorValue = armorValue;
        this.mainHandItemId = mainHandItemId;
        this.hostile = hostile;
        this.occlusionVisible = occlusionVisible;
        this.boundingHeight = boundingHeight;
        this.eyeHeight = eyeHeight;
        this.detailText = detailText;
    }

    public TargetType getType() { return type; }
    public String getUuid() { return uuid; }
    public String getRegistryId() { return registryId; }
    public String getDisplayName() { return displayName; }
    public double getRelativeX() { return relativeX; }
    public double getRelativeY() { return relativeY; }
    public double getRelativeZ() { return relativeZ; }
    public double getWorldX() { return worldX; }
    public double getWorldY() { return worldY; }
    public double getWorldZ() { return worldZ; }
    public double getDistance() { return distance; }
    public float getHealth() { return health; }
    public float getMaxHealth() { return maxHealth; }
    public float getAttackDamage() { return attackDamage; }
    public float getArmorValue() { return armorValue; }
    public String getMainHandItemId() { return mainHandItemId; }
    public boolean isHostile() { return hostile; }
    public boolean isOcclusionVisible() { return occlusionVisible; }
    public float getBoundingHeight() { return boundingHeight; }
    public float getEyeHeight() { return eyeHeight; }
    public String getDetailText() { return detailText; }
}
