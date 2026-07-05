package com.rheinmetal.tianshu.function.llm.runtime;

public record LlmContextBudgetSnapshot(
        int requestedContextSize,
        int trainingContextSize,
        int memoryContextSize,
        int plannedContextSize,
        int promptTokenBudget,
        int promptMarginTokens,
        long safetyMarginBytes,
        boolean reliable,
        String limitation
) {
    public LlmContextBudgetSnapshot {
        requestedContextSize = Math.max(0, requestedContextSize);
        trainingContextSize = Math.max(0, trainingContextSize);
        memoryContextSize = memoryContextSize < 0 ? -1 : memoryContextSize;
        plannedContextSize = Math.max(0, plannedContextSize);
        promptTokenBudget = Math.max(0, promptTokenBudget);
        promptMarginTokens = Math.max(0, promptMarginTokens);
        safetyMarginBytes = Math.max(0L, safetyMarginBytes);
        limitation = limitation == null ? "" : limitation.trim();
    }

    public static LlmContextBudgetSnapshot unavailable(String limitation) {
        return new LlmContextBudgetSnapshot(0, 0, -1, 0, 0, 0, 0L, false, limitation);
    }
}
