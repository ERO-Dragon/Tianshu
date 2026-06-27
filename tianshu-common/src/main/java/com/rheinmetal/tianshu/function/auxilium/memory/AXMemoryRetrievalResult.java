package com.rheinmetal.tianshu.function.auxilium.memory;

import java.util.List;

public record AXMemoryRetrievalResult(List<AXMemoryBlockView> blocks) {
    public AXMemoryRetrievalResult {
        blocks = blocks == null ? List.of() : blocks.stream()
                .filter(view -> view != null && !view.isEmpty())
                .toList();
    }

    public static AXMemoryRetrievalResult empty() {
        return new AXMemoryRetrievalResult(List.of());
    }
}
