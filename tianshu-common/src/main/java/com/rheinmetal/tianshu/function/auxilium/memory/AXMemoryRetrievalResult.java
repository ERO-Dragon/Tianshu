package com.rheinmetal.tianshu.function.auxilium.memory;

import java.util.List;

public record AXMemoryRetrievalResult(List<AXMemoryBlockView> blocks, List<AXMemoryRetrievalTrace> traces) {
    public AXMemoryRetrievalResult(List<AXMemoryBlockView> blocks) {
        this(blocks, List.of());
    }

    public AXMemoryRetrievalResult {
        blocks = blocks == null ? List.of() : blocks.stream()
                .filter(view -> view != null && !view.isEmpty())
                .toList();
        traces = traces == null ? List.of() : traces.stream()
                .filter(trace -> trace != null && !trace.isEmpty())
                .toList();
    }

    public static AXMemoryRetrievalResult empty() {
        return new AXMemoryRetrievalResult(List.of(), List.of());
    }
}
