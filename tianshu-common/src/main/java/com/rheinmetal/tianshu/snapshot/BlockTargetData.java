package com.rheinmetal.tianshu.snapshot;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

public final class BlockTargetData {

    public final String blockId;
    public final String displayName;
    public final Map<String, String> blockStateProperties;
    public final boolean hasBlockEntity;
    public final Map<String, String> blockEntityData;
    public final Set<String> blockTags;

    public BlockTargetData(
            String blockId,
            String displayName,
            Map<String, String> blockStateProperties,
            boolean hasBlockEntity,
            Map<String, String> blockEntityData,
            Set<String> blockTags
    ) {
        this.blockId = blockId;
        this.displayName = displayName;
        this.blockStateProperties = blockStateProperties != null ? blockStateProperties : Collections.emptyMap();
        this.hasBlockEntity = hasBlockEntity;
        this.blockEntityData = blockEntityData != null ? blockEntityData : Collections.emptyMap();
        this.blockTags = blockTags != null ? blockTags : Collections.emptySet();
    }

    public String getBlockId() { return blockId; }
    public String getDisplayName() { return displayName; }
    public Map<String, String> getBlockStateProperties() { return blockStateProperties; }
    public boolean isHasBlockEntity() { return hasBlockEntity; }
    public Map<String, String> getBlockEntityData() { return blockEntityData; }
    public Set<String> getBlockTags() { return blockTags; }
}
