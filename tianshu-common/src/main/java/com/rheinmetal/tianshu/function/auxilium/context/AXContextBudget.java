package com.rheinmetal.tianshu.function.auxilium.context;

public record AXContextBudget(int maxSystemChars, int maxMemoryItems, int maxShortTermTurns, int maxDynamicRagItems) {
    public static final AXContextBudget DEFAULT = fromPolicy(AXMemoryWindowPolicy.DEFAULT);

    public AXContextBudget {
        maxSystemChars = Math.max(1000, maxSystemChars);
        maxMemoryItems = Math.max(0, maxMemoryItems);
        maxShortTermTurns = Math.max(0, maxShortTermTurns);
        maxDynamicRagItems = Math.max(0, maxDynamicRagItems);
    }

    public static AXContextBudget fromPolicy(AXMemoryWindowPolicy policy) {
        AXMemoryWindowPolicy effectivePolicy = policy == null ? AXMemoryWindowPolicy.DEFAULT : policy;
        int maxSystemChars = Math.max(4000, effectivePolicy.chatInputTokenBudget() * 2);
        int maxMemoryItems = Math.max(0, effectivePolicy.userConventionChatTokenBudget() / 100);
        int maxShortTermTurns = Math.max(0, effectivePolicy.recentRawChatTokenBudget() / 500);
        int maxDynamicRagItems = Math.max(0, effectivePolicy.dynamicRagChatTokenBudget() / 50);
        return new AXContextBudget(maxSystemChars, maxMemoryItems, maxShortTermTurns, maxDynamicRagItems);
    }
}
