package com.rheinmetal.tianshu.function.auxilium.memory;

import java.util.List;

public record AXMemoryBlockView(
        AXStmBlock block,
        List<String> attachedMessages
) {
    public AXMemoryBlockView {
        attachedMessages = attachedMessages == null ? List.of() : attachedMessages.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    public boolean isEmpty() {
        return block == null || block.isEmpty();
    }

    public String content() {
        return block == null ? "" : block.content();
    }

    public int estimatedTokens() {
        if (block == null) {
            return 0;
        }
        int total = block.estimatedTokens();
        AXTokenEstimator estimator = new AXTokenEstimator();
        for (String message : attachedMessages) {
            total += estimator.estimate(message);
        }
        return Math.max(0, total);
    }

    public static AXMemoryBlockView of(AXStmBlock block) {
        return new AXMemoryBlockView(block, List.of());
    }
}
