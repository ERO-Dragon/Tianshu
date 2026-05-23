package com.rheinmetal.tianshu.function.auxilium.context;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.memory.AXMemorySnapshot;
import com.rheinmetal.tianshu.function.auxilium.memory.AXMemorySystem;
import com.rheinmetal.tianshu.function.auxilium.rag.DynamicRagCandidateBuilder;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;

import java.util.List;

public final class AXContextCollector {
    private final AXMemorySystem memorySystem;
    private final DynamicRagCandidateBuilder ragCandidateBuilder;

    public AXContextCollector(AXMemorySystem memorySystem, DynamicRagCandidateBuilder ragCandidateBuilder) {
        this.memorySystem = memorySystem;
        this.ragCandidateBuilder = ragCandidateBuilder;
    }

    public AXContextSnapshot collect(AXScope scope, AXRequest request) {
        AXScope effectiveScope = scope == null ? AXScope.unknown() : scope;
        AXMemorySnapshot memory = memorySystem == null ? AXMemorySnapshot.empty(effectiveScope) : memorySystem.load(effectiveScope);
        return new AXContextSnapshot(
                effectiveScope,
                memory,
                ragCandidateBuilder == null ? List.of() : ragCandidateBuilder.build(effectiveScope, request),
                request == null ? "" : request.providedContext(),
                memorySystem != null && memorySystem.hasMemoryRagEntries(effectiveScope)
        );
    }
}
