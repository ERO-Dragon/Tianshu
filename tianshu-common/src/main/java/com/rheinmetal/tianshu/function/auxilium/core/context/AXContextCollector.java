package com.rheinmetal.tianshu.function.auxilium.core.context;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXMemorySnapshot;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXMemorySystem;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRecentDialogueSnapshot;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRecentDialogueSystem;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.module.gamecontext.AXDynamicFact;

import java.util.List;

public final class AXContextCollector {
    private final AXMemorySystem memorySystem;
    private final AXRecentDialogueSystem recentDialogueSystem;

    public AXContextCollector(AXMemorySystem memorySystem) {
        this(memorySystem, null);
    }

    public AXContextCollector(AXMemorySystem memorySystem, AXRecentDialogueSystem recentDialogueSystem) {
        this.memorySystem = memorySystem;
        this.recentDialogueSystem = recentDialogueSystem;
    }

    public AXContextSnapshot collect(AXScope scope, AXRequest request) {
        return collect(scope, request, List.of());
    }

    public AXContextSnapshot collect(AXScope scope, AXRequest request, List<AXDynamicFact> dynamicFacts) {
        AXScope effectiveScope = scope == null ? AXScope.unknown() : scope;
        AXMemorySnapshot memory = memorySystem == null ? AXMemorySnapshot.empty(effectiveScope) : memorySystem.load(effectiveScope);
        AXRecentDialogueSnapshot recentDialogue = recentDialogueSystem == null
                ? AXRecentDialogueSnapshot.empty()
                : recentDialogueSystem.snapshot(effectiveScope);
        return new AXContextSnapshot(
                effectiveScope,
                memory,
                recentDialogue,
                dynamicFacts,
                request == null ? "" : request.deliverySnapshot()
        );
    }
}
