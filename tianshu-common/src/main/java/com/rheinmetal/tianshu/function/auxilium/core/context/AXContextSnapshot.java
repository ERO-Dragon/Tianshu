package com.rheinmetal.tianshu.function.auxilium.core.context;

import com.rheinmetal.tianshu.function.auxilium.module.memory.AXMemorySnapshot;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRecentDialogueSnapshot;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;

import com.rheinmetal.tianshu.function.auxilium.module.gamecontext.AXDynamicFact;

import java.util.List;

public record AXContextSnapshot(
        AXScope scope,
        AXMemorySnapshot memory,
        AXRecentDialogueSnapshot recentDialogue,
        List<AXDynamicFact> dynamicFacts,
        String deliverySnapshot
) {
    public AXContextSnapshot {
        if (scope == null) {
            scope = AXScope.unknown();
        }
        if (memory == null) {
            memory = AXMemorySnapshot.empty(scope);
        }
        if (recentDialogue == null) {
            recentDialogue = AXRecentDialogueSnapshot.empty();
        }
        dynamicFacts = dynamicFacts == null ? List.of() : List.copyOf(dynamicFacts);
        deliverySnapshot = deliverySnapshot == null ? "" : deliverySnapshot.trim();
    }

    public AXContextSnapshot(AXScope scope, AXMemorySnapshot memory, List<AXDynamicFact> dynamicFacts, String deliverySnapshot) {
        this(scope, memory, null, dynamicFacts, deliverySnapshot);
    }

    public static AXContextSnapshot empty() {
        return new AXContextSnapshot(AXScope.unknown(), null, null, List.of(), "");
    }
}
