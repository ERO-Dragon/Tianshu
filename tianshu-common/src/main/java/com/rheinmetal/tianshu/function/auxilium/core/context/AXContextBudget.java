package com.rheinmetal.tianshu.function.auxilium.core.context;

public record AXContextBudget(
        int maxSystemChars,
        int maxMemoryItems,
        int maxShortTermTurns,
        int maxStaticContentItems,
        int maxDynamicContentItems,
        int memoryTokenBudget
) {
    public static final AXContextBudget DEFAULT = fromPolicy(AXMemoryWindowPolicy.DEFAULT);

    public AXContextBudget(int maxSystemChars, int maxMemoryItems, int maxShortTermTurns, int maxGameContextItems) {
        this(maxSystemChars, maxMemoryItems, maxShortTermTurns, maxGameContextItems, maxGameContextItems);
    }

    public AXContextBudget(int maxSystemChars, int maxMemoryItems, int maxShortTermTurns, int maxStaticContentItems, int maxDynamicContentItems) {
        this(maxSystemChars, maxMemoryItems, maxShortTermTurns, maxStaticContentItems, maxDynamicContentItems, AXMemoryWindowPolicy.DEFAULT.shortTermChatTokenBudget());
    }

    public AXContextBudget {
        maxSystemChars = Math.max(1000, maxSystemChars);
        maxMemoryItems = Math.max(0, maxMemoryItems);
        maxShortTermTurns = Math.max(0, maxShortTermTurns);
        maxStaticContentItems = Math.max(0, maxStaticContentItems);
        maxDynamicContentItems = Math.max(0, maxDynamicContentItems);
        memoryTokenBudget = Math.max(0, memoryTokenBudget);
    }

    public static AXContextBudget fromPolicy(AXMemoryWindowPolicy policy) {
        AXMemoryWindowPolicy effectivePolicy = policy == null ? AXMemoryWindowPolicy.DEFAULT : policy;
        int maxSystemChars = Math.max(4000, effectivePolicy.chatInputTokenBudget() * 2);
        int maxMemoryItems = Math.max(0, effectivePolicy.memoryRagTokenBudget() / 100);
        int maxShortTermTurns = Math.max(0, effectivePolicy.recentRawChatTokenBudget() / 500);
        int maxStaticContentItems = Math.max(0, effectivePolicy.staticContentTokenBudget() / 100);
        int maxDynamicContentItems = Math.max(0, effectivePolicy.dynamicContentTokenBudget() / 75);
        return new AXContextBudget(
                maxSystemChars,
                maxMemoryItems,
                maxShortTermTurns,
                maxStaticContentItems,
                maxDynamicContentItems,
                effectivePolicy.shortTermChatTokenBudget()
        );
    }
}
