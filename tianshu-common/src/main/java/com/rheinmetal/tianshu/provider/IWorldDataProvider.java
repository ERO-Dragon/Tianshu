package com.rheinmetal.tianshu.provider;

import com.rheinmetal.tianshu.snapshot.*;

import java.util.Set;

public interface IWorldDataProvider {

    BlockTargetData getBlockAt(BlockPosValue pos);

    Set<String> getBlockTags(String blockId);

    boolean isLineOfSightBlocked(double x1, double y1, double z1, double x2, double y2, double z2);

    BlockTargetData getCrosshairBlockTarget();

    Set<BlockPosValue> getDirtyChunkSlice(int radius, BlockPosValue lastPlayerPos, int threshold);
}
