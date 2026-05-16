package com.rheinmetal.tianshu.function.assistant.context;

import com.rheinmetal.tianshu.api.ITianshuConfig;

public record AssistantMemoryWindowPolicy(
        int chatInputTokenBudget,
        int recentRawChatTokenBudget,
        int shortTermChatTokenBudget,
        int memoryRagTokenBudget,
        int userConventionChatTokenBudget,
        int dynamicRagChatTokenBudget,
        int recentRawKeepTokenTarget,
        int recentRawKeepTokenMax,
        int shortTermCompressTokenTarget,
        int shortTermCompressTokenMax,
        int maxRawEstimatedTokens,
        int maxRawCharacters,
        int shortTermChatBlockLimit,
        long conversationPauseMillis
) {
    public static final AssistantMemoryWindowPolicy DEFAULT = new AssistantMemoryWindowPolicy(
            8000,
            4000,
            1500,
            1000,
            500,
            500,
            5000,
            8000,
            7000,
            10000,
            28000,
            120000,
            3,
            60000L
    );

    public AssistantMemoryWindowPolicy {
        chatInputTokenBudget = Math.max(1000, chatInputTokenBudget);
        recentRawChatTokenBudget = Math.max(0, recentRawChatTokenBudget);
        shortTermChatTokenBudget = Math.max(0, shortTermChatTokenBudget);
        memoryRagTokenBudget = Math.max(0, memoryRagTokenBudget);
        userConventionChatTokenBudget = Math.max(0, userConventionChatTokenBudget);
        dynamicRagChatTokenBudget = Math.max(0, dynamicRagChatTokenBudget);
        recentRawKeepTokenTarget = Math.max(0, recentRawKeepTokenTarget);
        recentRawKeepTokenMax = Math.max(recentRawKeepTokenTarget, recentRawKeepTokenMax);
        shortTermCompressTokenTarget = Math.max(0, shortTermCompressTokenTarget);
        shortTermCompressTokenMax = Math.max(shortTermCompressTokenTarget, shortTermCompressTokenMax);
        maxRawEstimatedTokens = Math.max(recentRawKeepTokenMax + shortTermCompressTokenMax, maxRawEstimatedTokens);
        maxRawCharacters = Math.max(0, maxRawCharacters);
        shortTermChatBlockLimit = Math.max(0, shortTermChatBlockLimit);
        conversationPauseMillis = Math.max(0L, conversationPauseMillis);
    }

    public static AssistantMemoryWindowPolicy fromConfig(ITianshuConfig config) {
        if (config == null) {
            return DEFAULT;
        }
        return new AssistantMemoryWindowPolicy(
                config.getLlmAssistantChatInputTokenBudget(),
                config.getLlmAssistantRecentRawChatTokenBudget(),
                config.getLlmAssistantShortTermChatTokenBudget(),
                config.getLlmMemoryRagTokenBudget(),
                config.getLlmAssistantUserConventionChatTokenBudget(),
                config.getLlmAssistantDynamicRagChatTokenBudget(),
                config.getLlmAssistantRecentRawKeepTokenTarget(),
                config.getLlmAssistantRecentRawKeepTokenMax(),
                config.getLlmAssistantShortTermCompressTokenTarget(),
                config.getLlmAssistantShortTermCompressTokenMax(),
                config.getLlmAssistantMaxRawEstimatedTokens(),
                config.getLlmAssistantMaxRawCharacters(),
                config.getLlmAssistantShortTermChatBlockLimit(),
                config.getLlmAssistantConversationPauseMillis()
        );
    }
}
