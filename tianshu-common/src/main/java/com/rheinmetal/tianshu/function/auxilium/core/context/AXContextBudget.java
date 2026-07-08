package com.rheinmetal.tianshu.function.auxilium.core.context;

public record AXContextBudget(
        int systemTokenBudget,
        int maxRetrievedMemoryItems,
        int maxRecentMemoryItems,
        int maxRecentRawDialogueTurns,
        int maxKnowledgeRagItems,
        int maxCurrentInputChars,
        int retrievedMemoryTokenBudget,
        int recentMemoryTokenBudget
) {
    public static final AXContextBudget DEFAULT = fromPolicy(AXMemoryWindowPolicy.DEFAULT);

    public AXContextBudget {
        systemTokenBudget = Math.max(0, systemTokenBudget);
        maxRetrievedMemoryItems = Math.max(0, maxRetrievedMemoryItems);
        maxRecentMemoryItems = Math.max(0, maxRecentMemoryItems);
        maxRecentRawDialogueTurns = Math.max(0, maxRecentRawDialogueTurns);
        maxKnowledgeRagItems = Math.max(0, maxKnowledgeRagItems);
        maxCurrentInputChars = Math.max(1000, maxCurrentInputChars);
        retrievedMemoryTokenBudget = Math.max(0, retrievedMemoryTokenBudget);
        recentMemoryTokenBudget = Math.max(0, recentMemoryTokenBudget);
    }

    public static AXContextBudget fromPolicy(AXMemoryWindowPolicy policy) {
        AXMemoryWindowPolicy effectivePolicy = policy == null ? AXMemoryWindowPolicy.DEFAULT : policy;
        int systemTokenBudget = effectivePolicy.chatSystemTokenBudget();
        int maxRetrievedMemoryItems = Math.max(0, effectivePolicy.retrievedMemoryTokenBudget() / 100);
        int maxRecentMemoryItems = Math.max(0, effectivePolicy.recentMemoryTokenBudget() / 100);
        int maxRecentRawDialogueTurns = Math.max(0, effectivePolicy.recentRawDialogueTokenBudget() / 100);
        int maxKnowledgeRagItems = Math.max(0, effectivePolicy.knowledgeRagTokenBudget() / 100);
        int maxCurrentInputChars = Math.max(1000, effectivePolicy.currentInputTokenBudget() * 2);
        return new AXContextBudget(
                systemTokenBudget,
                maxRetrievedMemoryItems,
                maxRecentMemoryItems,
                maxRecentRawDialogueTurns,
                maxKnowledgeRagItems,
                maxCurrentInputChars,
                effectivePolicy.retrievedMemoryTokenBudget(),
                effectivePolicy.recentMemoryTokenBudget()
        );
    }
}
