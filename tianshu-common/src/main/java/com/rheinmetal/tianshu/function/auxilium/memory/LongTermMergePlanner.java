package com.rheinmetal.tianshu.function.auxilium.memory;

import com.rheinmetal.tianshu.function.auxilium.context.AXMemoryWindowPolicy;

import java.util.List;
import java.util.Optional;

public final class LongTermMergePlanner {
    private final int mergeBatchSize;

    public LongTermMergePlanner(AXMemoryWindowPolicy policy) {
        this.mergeBatchSize = 4;
    }

    public Optional<List<ShortTermMemoryBlock>> plan(List<ShortTermMemoryBlock> blocks) {
        if (blocks == null || blocks.size() < mergeBatchSize) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(blocks.subList(0, mergeBatchSize)));
    }
}
