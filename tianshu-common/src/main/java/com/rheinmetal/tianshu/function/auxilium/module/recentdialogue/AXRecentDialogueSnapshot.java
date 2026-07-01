package com.rheinmetal.tianshu.function.auxilium.module.recentdialogue;

import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRawTurn;

import java.util.List;

public record AXRecentDialogueSnapshot(List<AXRawTurn> turns) {
    public AXRecentDialogueSnapshot {
        turns = turns == null ? List.of() : turns.stream()
                .filter(turn -> turn != null && !turn.isEmpty())
                .toList();
    }

    public static AXRecentDialogueSnapshot empty() {
        return new AXRecentDialogueSnapshot(List.of());
    }

    public boolean isEmpty() {
        return turns.isEmpty();
    }
}
