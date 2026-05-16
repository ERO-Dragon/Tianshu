package com.rheinmetal.tianshu.function.llm.gateway;

public record LlmGatewayPolicy(
        int maxPendingTasks,
        int maxPendingTasksPerSource,
        int maxMessages,
        int maxMessageCharacters,
        int maxDynamicFacts,
        int maxDynamicFactCharacters,
        int defaultMaxTokens
) {
    public static final LlmGatewayPolicy DEFAULT = new LlmGatewayPolicy(64, 8, 32, 12000, 32, 8000, 512);

    public LlmGatewayPolicy {
        maxPendingTasks = Math.max(0, maxPendingTasks);
        maxPendingTasksPerSource = Math.max(1, maxPendingTasksPerSource);
        maxMessages = Math.max(1, maxMessages);
        maxMessageCharacters = Math.max(1, maxMessageCharacters);
        maxDynamicFacts = Math.max(0, maxDynamicFacts);
        maxDynamicFactCharacters = Math.max(0, maxDynamicFactCharacters);
        defaultMaxTokens = Math.max(0, defaultMaxTokens);
    }
}
