package com.rheinmetal.tianshu.function.assistant.context;

public record AssistantContextBudget(int maxSystemChars, int maxMemoryItems, int maxShortTermTurns, int maxDynamicRagItems) {
    public static final AssistantContextBudget DEFAULT = fromPolicy(AssistantMemoryWindowPolicy.DEFAULT);

    public AssistantContextBudget {
        maxSystemChars = Math.max(1000, maxSystemChars);
        maxMemoryItems = Math.max(0, maxMemoryItems);
        maxShortTermTurns = Math.max(0, maxShortTermTurns);
        maxDynamicRagItems = Math.max(0, maxDynamicRagItems);
    }

    public static AssistantContextBudget fromPolicy(AssistantMemoryWindowPolicy policy) {
        AssistantMemoryWindowPolicy effectivePolicy = policy == null ? AssistantMemoryWindowPolicy.DEFAULT : policy;
        int maxSystemChars = Math.max(4000, effectivePolicy.chatInputTokenBudget() * 2);
        int maxMemoryItems = Math.max(0, effectivePolicy.userConventionChatTokenBudget() / 100);
        int maxShortTermTurns = Math.max(0, effectivePolicy.recentRawChatTokenBudget() / 500);
        int maxDynamicRagItems = Math.max(0, effectivePolicy.dynamicRagChatTokenBudget() / 50);
        return new AssistantContextBudget(maxSystemChars, maxMemoryItems, maxShortTermTurns, maxDynamicRagItems);
    }
}
