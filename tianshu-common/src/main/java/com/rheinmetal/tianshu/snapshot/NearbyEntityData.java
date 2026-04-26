package com.rheinmetal.tianshu.snapshot;

import java.util.Objects;

public final class NearbyEntityData {

    public final String entityId;

    /**
     * 期望填入经过 Minecraft 本地化处理后的显示名称（如通过 getName().getString() 获取）。
     * 严禁填入未经翻译的注册表 ID（如 "minecraft.zombie"），必须是对应语言的文本（如 "僵尸"），
     * 以防止 2B 小模型因上下文充斥英文而产生语言混乱。
     */
    public final String displayName;

    public final double relativeX;
    public final double relativeY;
    public final double relativeZ;
    public final double horizontalAngle;
    public final double distance;
    public final boolean hostile;

    public NearbyEntityData(
            String entityId,
            String displayName,
            double relativeX,
            double relativeY,
            double relativeZ,
            double horizontalAngle,
            double distance,
            boolean hostile
    ) {
        this.entityId = entityId;
        this.displayName = displayName;
        this.relativeX = relativeX;
        this.relativeY = relativeY;
        this.relativeZ = relativeZ;
        this.horizontalAngle = horizontalAngle;
        this.distance = distance;
        this.hostile = hostile;
    }

    public String getEntityId() { return entityId; }
    public String getDisplayName() { return displayName; }
    public double getRelativeX() { return relativeX; }
    public double getRelativeY() { return relativeY; }
    public double getRelativeZ() { return relativeZ; }
    public double getHorizontalAngle() { return horizontalAngle; }
    public double getDistance() { return distance; }
    public boolean isHostile() { return hostile; }

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
