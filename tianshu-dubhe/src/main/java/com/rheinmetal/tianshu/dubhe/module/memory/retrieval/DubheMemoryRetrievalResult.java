package com.rheinmetal.tianshu.dubhe.module.memory.retrieval;

import com.rheinmetal.tianshu.dubhe.module.memory.DubheMemoryBlockView;

import java.util.List;

public record DubheMemoryRetrievalResult(List<DubheMemoryBlockView> blocks, List<DubheMemoryRetrievalTrace> traces) {
    public DubheMemoryRetrievalResult(List<DubheMemoryBlockView> blocks) {
        this(blocks, List.of());
    }

    public DubheMemoryRetrievalResult {
        blocks = blocks == null ? List.of() : blocks.stream()
                .filter(view -> view != null && !view.isEmpty())
                .toList();
        traces = traces == null ? List.of() : traces.stream()
                .filter(trace -> trace != null && !trace.isEmpty())
                .toList();
    }

    public static DubheMemoryRetrievalResult empty() {
        return new DubheMemoryRetrievalResult(List.of(), List.of());
    }
}
