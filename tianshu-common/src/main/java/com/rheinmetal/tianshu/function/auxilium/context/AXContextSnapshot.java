package com.rheinmetal.tianshu.function.auxilium.context;

import com.rheinmetal.tianshu.function.auxilium.memory.AXMemorySnapshot;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;

import java.util.List;

public record AXContextSnapshot(AXScope scope, AXMemorySnapshot memory, List<AXRuntimeContextFact> runtimeContextFacts, String providedContext) {
    public AXContextSnapshot {
        if (scope == null) {
            scope = AXScope.unknown();
        }
        if (memory == null) {
            memory = AXMemorySnapshot.empty(scope);
        }
        runtimeContextFacts = runtimeContextFacts == null ? List.of() : List.copyOf(runtimeContextFacts);
        providedContext = providedContext == null ? "" : providedContext.trim();
    }

    public static AXContextSnapshot empty() {
        return new AXContextSnapshot(AXScope.unknown(), null, List.of(), "");
    }
}
