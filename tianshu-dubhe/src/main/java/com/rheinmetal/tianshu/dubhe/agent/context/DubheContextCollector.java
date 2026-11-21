package com.rheinmetal.tianshu.dubhe.agent.context;

import com.rheinmetal.tianshu.dubhe.DubheRequest;
import com.rheinmetal.tianshu.dubhe.module.memory.DubheMemorySnapshot;
import com.rheinmetal.tianshu.dubhe.module.memory.DubheMemorySystem;
import com.rheinmetal.tianshu.dubhe.module.recentdialogue.DubheRecentDialogueSnapshot;
import com.rheinmetal.tianshu.dubhe.module.recentdialogue.DubheRecentDialogueSystem;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScope;
import com.rheinmetal.tianshu.dubhe.dialogue.gamecontext.DubheDynamicFact;

import java.util.List;

public final class DubheContextCollector {
    private final DubheMemorySystem memorySystem;
    private final DubheRecentDialogueSystem recentDialogueSystem;

    public DubheContextCollector(DubheMemorySystem memorySystem) {
        this(memorySystem, null);
    }

    public DubheContextCollector(DubheMemorySystem memorySystem, DubheRecentDialogueSystem recentDialogueSystem) {
        this.memorySystem = memorySystem;
        this.recentDialogueSystem = recentDialogueSystem;
    }

    public DubheContextSnapshot collect(DubheScope scope, DubheRequest request) {
        return collect(scope, request, List.of());
    }

    public DubheContextSnapshot collect(DubheScope scope, DubheRequest request, List<DubheDynamicFact> dynamicFacts) {
        DubheScope effectiveScope = scope == null ? DubheScope.unknown() : scope;
        DubheMemorySnapshot memory = memorySystem == null ? DubheMemorySnapshot.empty() : memorySystem.load(effectiveScope);
        DubheRecentDialogueSnapshot recentDialogue = recentDialogueSystem == null
                ? DubheRecentDialogueSnapshot.empty()
                : recentDialogueSystem.snapshot(effectiveScope);
        return new DubheContextSnapshot(
                effectiveScope,
                memory,
                recentDialogue,
                dynamicFacts,
                request == null ? "" : request.deliverySnapshot()
        );
    }
}
