package com.rheinmetal.tianshu.dubhe.agent.context;

import com.rheinmetal.tianshu.dubhe.module.memory.DubheMemorySnapshot;
import com.rheinmetal.tianshu.dubhe.module.recentdialogue.DubheRecentDialogueSnapshot;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScope;

import com.rheinmetal.tianshu.dubhe.dialogue.gamecontext.DubheDynamicFact;

import java.util.List;

public record DubheContextSnapshot(
        DubheScope scope,
        DubheMemorySnapshot memory,
        DubheRecentDialogueSnapshot recentDialogue,
        List<DubheDynamicFact> dynamicFacts,
        String deliverySnapshot
) {
    public DubheContextSnapshot {
        if (scope == null) {
            scope = DubheScope.unknown();
        }
        if (memory == null) {
            memory = DubheMemorySnapshot.empty();
        }
        if (recentDialogue == null) {
            recentDialogue = DubheRecentDialogueSnapshot.empty();
        }
        dynamicFacts = dynamicFacts == null ? List.of() : List.copyOf(dynamicFacts);
        deliverySnapshot = deliverySnapshot == null ? "" : deliverySnapshot.trim();
    }

    public DubheContextSnapshot(DubheScope scope, DubheMemorySnapshot memory, List<DubheDynamicFact> dynamicFacts, String deliverySnapshot) {
        this(scope, memory, null, dynamicFacts, deliverySnapshot);
    }

    public static DubheContextSnapshot empty() {
        return new DubheContextSnapshot(DubheScope.unknown(), null, null, List.of(), "");
    }
}
