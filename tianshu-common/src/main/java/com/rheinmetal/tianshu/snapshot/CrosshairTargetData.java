package com.rheinmetal.tianshu.snapshot;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class CrosshairTargetData {

    public final TargetType type;
    public final String registryId;

    /**
     * 期望填入经过 Minecraft 本地化处理后的显示名称（如通过 getHoverName().getString() 获取）。
     * 严禁填入未经翻译的注册表 ID（如 "minecraft.zombie"），必须是对应语言的文本（如 "僵尸"），
     * 以防止 2B 小模型因上下文充斥英文而产生语言混乱。
     */
    public final String displayName;

    public final Map<String, String> blockStateProperties;
    public final boolean hasBlockEntity;
    public final Map<String, String> blockEntityData;

    public final String mainHandItemId;
    public final String offHandItemId;
    public final float entityHealth;

    public final String biomeId;

    public CrosshairTargetData(
            TargetType type,
            String registryId,
            String displayName,
            Map<String, String> blockStateProperties,
            boolean hasBlockEntity,
            Map<String, String> blockEntityData,
            String mainHandItemId,
            String offHandItemId,
            float entityHealth,
            String biomeId
    ) {
        this.type = type;
        this.registryId = registryId;
        this.displayName = displayName;
        this.blockStateProperties = blockStateProperties != null ? blockStateProperties : Collections.emptyMap();
        this.hasBlockEntity = hasBlockEntity;
        this.blockEntityData = blockEntityData != null ? blockEntityData : Collections.emptyMap();
        this.mainHandItemId = mainHandItemId;
        this.offHandItemId = offHandItemId;
        this.entityHealth = entityHealth;
        this.biomeId = biomeId;
    }

    public TargetType getType() { return type; }
    public String getRegistryId() { return registryId; }
    public String getDisplayName() { return displayName; }
    public Map<String, String> getBlockStateProperties() { return blockStateProperties; }
    public boolean isHasBlockEntity() { return hasBlockEntity; }
    public Map<String, String> getBlockEntityData() { return blockEntityData; }
    public String getMainHandItemId() { return mainHandItemId; }
    public String getOffHandItemId() { return offHandItemId; }
    public float getEntityHealth() { return entityHealth; }
    public String getBiomeId() { return biomeId; }

    public static CrosshairTargetData forBlock(
            String blockId, String displayName,
            Map<String, String> stateProperties,
            boolean hasBlockEntity,
            Map<String, String> blockEntityData
    ) {
        return new CrosshairTargetData(
                TargetType.BLOCK, blockId, displayName,
                stateProperties, hasBlockEntity,
                blockEntityData,
                null, null, 0f, null
        );
    }

    public static CrosshairTargetData forEntity(
            String entityId, String displayName,
            String mainHandItemId, String offHandItemId, float health
    ) {
        return new CrosshairTargetData(
                TargetType.ENTITY, entityId, displayName,
                null, false, null,
                mainHandItemId, offHandItemId, health, null
        );
    }

    public static CrosshairTargetData forBiome(String biomeId, String displayName) {
        return new CrosshairTargetData(
                TargetType.BIOME, biomeId, displayName,
                null, false, null,
                null, null, 0f, biomeId
        );
    }

    public static CrosshairTargetData forVoid(String biomeId, String displayName) {
        return new CrosshairTargetData(
                TargetType.VOID, null, displayName,
                null, false, null,
                null, null, 0f, biomeId
        );
    }
}
