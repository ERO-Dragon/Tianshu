package com.rheinmetal.tianshu.dubhe.module.recentdialogue;


import java.util.List;

public record DubheRecentDialogueSnapshot(List<DubheRawTurn> turns) {
    public DubheRecentDialogueSnapshot {
        turns = turns == null ? List.of() : turns.stream()
                .filter(turn -> turn != null && !turn.isEmpty())
                .toList();
    }

    public static DubheRecentDialogueSnapshot empty() {
        return new DubheRecentDialogueSnapshot(List.of());
    }

    public boolean isEmpty() {
        return turns.isEmpty();
    }
}
