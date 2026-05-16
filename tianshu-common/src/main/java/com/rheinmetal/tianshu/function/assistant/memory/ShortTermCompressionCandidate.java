package com.rheinmetal.tianshu.function.assistant.memory;

import java.util.List;

public record ShortTermCompressionCandidate(
        List<ConversationTurn> turns,
        boolean pauseBoundary,
        boolean forcedByMaxWindow,
        int estimatedTokens
) {
    public ShortTermCompressionCandidate {
        turns = turns == null ? List.of() : turns.stream()
                .filter(turn -> turn != null && !turn.isEmpty())
                .toList();
        estimatedTokens = estimatedTokens <= 0 ? turns.stream().mapToInt(ConversationTurn::estimatedTokens).sum() : estimatedTokens;
    }

    public boolean isEmpty() {
        return turns.isEmpty();
    }
}
