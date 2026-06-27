package com.rheinmetal.tianshu.function.auxilium.context;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.memory.AXMemoryBlockView;
import com.rheinmetal.tianshu.function.auxilium.memory.AXMemorySnapshot;
import com.rheinmetal.tianshu.function.auxilium.memory.AXMemorySystem;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;

import java.util.List;

public final class AXContextCollector {
    private final AXMemorySystem memorySystem;

    public AXContextCollector(AXMemorySystem memorySystem) {
        this.memorySystem = memorySystem;
    }

    public AXContextSnapshot collect(AXScope scope, AXRequest request) {
        return collect(scope, request, List.of());
    }

    public AXContextSnapshot collect(AXScope scope, AXRequest request, List<AXRuntimeContextFact> runtimeContextFacts) {
        return collect(scope, request, runtimeContextFacts, null);
    }

    public AXContextSnapshot collect(AXScope scope, AXRequest request, List<AXRuntimeContextFact> runtimeContextFacts, List<AXMemoryBlockView> selectedMemoryBlocks) {
        AXScope effectiveScope = scope == null ? AXScope.unknown() : scope;
        AXMemorySnapshot memory = memorySystem == null ? AXMemorySnapshot.empty(effectiveScope) : memorySystem.load(effectiveScope);
        if (selectedMemoryBlocks != null) {
            memory = memory.withPlayerMemoryBlocks(selectedMemoryBlocks);
        }
        return new AXContextSnapshot(
                effectiveScope,
                memory,
                runtimeContextFacts,
                request == null ? "" : request.providedContext()
        );
    }
}
