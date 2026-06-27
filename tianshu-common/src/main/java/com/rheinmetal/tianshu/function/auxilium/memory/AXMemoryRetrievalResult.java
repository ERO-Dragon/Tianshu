package com.rheinmetal.tianshu.function.auxilium.memory;

import java.util.List;

public record AXMemoryRetrievalResult(List<AXStmBlock> blocks) {
    public AXMemoryRetrievalResult {
        blocks = blocks == null ? List.of() : blocks.stream()
                .filter(block -> block != null && !block.isEmpty())
                .toList();
    }

    public static AXMemoryRetrievalResult empty() {
        return new AXMemoryRetrievalResult(List.of());
    }
}
