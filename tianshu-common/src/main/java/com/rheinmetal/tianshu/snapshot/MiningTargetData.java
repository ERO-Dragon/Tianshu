package com.rheinmetal.tianshu.snapshot;

public final class MiningTargetData {

    public final String blockId;
    public final String displayName;
    public final boolean highValue;
    public final float progress;
    public final BlockPosValue blockPos;

    public MiningTargetData(
            String blockId,
            String displayName,
            boolean highValue,
            float progress,
            BlockPosValue blockPos
    ) {
        this.blockId = blockId;
        this.displayName = displayName;
        this.highValue = highValue;
        this.progress = progress;
        this.blockPos = blockPos;
    }

    public String getBlockId() { return blockId; }
    public String getDisplayName() { return displayName; }
    public boolean isHighValue() { return highValue; }
    public float getProgress() { return progress; }
    public BlockPosValue getBlockPos() { return blockPos; }
}
