package com.rheinmetal.tianshu.function.auxilium.context;

import com.rheinmetal.tianshu.function.auxilium.memory.AXMemorySnapshot;
import com.rheinmetal.tianshu.function.auxilium.rag.DynamicRagCandidate;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;

import java.util.List;

public record AXContextSnapshot(AXScope scope, AXMemorySnapshot memory, List<DynamicRagCandidate> dynamicRagCandidates, String providedContext, boolean memoryRagAvailable) {
    public AXContextSnapshot {
        if (scope == null) {
            scope = AXScope.unknown();
        }
        if (memory == null) {
            memory = AXMemorySnapshot.empty(scope);
        }
        dynamicRagCandidates = dynamicRagCandidates == null ? List.of() : List.copyOf(dynamicRagCandidates);
        providedContext = providedContext == null ? "" : providedContext.trim();
    }

    public AXContextSnapshot(AXScope scope, AXMemorySnapshot memory, List<DynamicRagCandidate> dynamicRagCandidates, String providedContext) {
        this(scope, memory, dynamicRagCandidates, providedContext, false);
    }
}
