package com.rheinmetal.tianshu.function.auxilium.context;

import com.rheinmetal.tianshu.api.ITianshuConfig;

public record AXMemoryWindowPolicy(
        int chatInputTokenBudget,
        int recentRawChatTokenBudget,
        int shortTermChatTokenBudget,
        int memoryRagTokenBudget,
        int userConventionChatTokenBudget,
        int runtimeContextChatTokenBudget,
        int recentRawKeepTokenTarget,
        int recentRawKeepTokenMax,
        int shortTermCompressTokenTarget,
        int shortTermCompressTokenMax,
        int maxRawEstimatedTokens,
        int maxRawCharacters,
        int shortTermChatBlockLimit,
        long conversationPauseMillis
) {
    public static final AXMemoryWindowPolicy DEFAULT = new AXMemoryWindowPolicy(
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

    public AXMemoryWindowPolicy {
        chatInputTokenBudget = Math.max(1000, chatInputTokenBudget);
        recentRawChatTokenBudget = Math.max(0, recentRawChatTokenBudget);
        shortTermChatTokenBudget = Math.max(0, shortTermChatTokenBudget);
        memoryRagTokenBudget = Math.max(0, memoryRagTokenBudget);
        userConventionChatTokenBudget = Math.max(0, userConventionChatTokenBudget);
        runtimeContextChatTokenBudget = Math.max(0, runtimeContextChatTokenBudget);
        recentRawKeepTokenTarget = Math.max(0, recentRawKeepTokenTarget);
        recentRawKeepTokenMax = Math.max(recentRawKeepTokenTarget, recentRawKeepTokenMax);
        shortTermCompressTokenTarget = Math.max(0, shortTermCompressTokenTarget);
        shortTermCompressTokenMax = Math.max(shortTermCompressTokenTarget, shortTermCompressTokenMax);
        maxRawEstimatedTokens = Math.max(recentRawKeepTokenMax + shortTermCompressTokenMax, maxRawEstimatedTokens);
        maxRawCharacters = Math.max(0, maxRawCharacters);
        shortTermChatBlockLimit = Math.max(0, shortTermChatBlockLimit);
        conversationPauseMillis = Math.max(0L, conversationPauseMillis);
    }

    public static AXMemoryWindowPolicy fromConfig(ITianshuConfig config) {
        if (config == null) {
            return DEFAULT;
        }
        return new AXMemoryWindowPolicy(
                config.getLlmAXChatInputTokenBudget(),
                config.getLlmAXRecentRawChatTokenBudget(),
                config.getLlmAXShortTermChatTokenBudget(),
                config.getLlmMemoryRagTokenBudget(),
                config.getLlmAXUserConventionChatTokenBudget(),
                runtimeContextBudget(config),
                config.getLlmAXRecentRawKeepTokenTarget(),
                config.getLlmAXRecentRawKeepTokenMax(),
                config.getLlmAXShortTermCompressTokenTarget(),
                config.getLlmAXShortTermCompressTokenMax(),
                config.getLlmAXMaxRawEstimatedTokens(),
                config.getLlmAXMaxRawCharacters(),
                config.getLlmAXShortTermChatBlockLimit(),
                config.getLlmAXConversationPauseMillis()
        );
    }

    private static int runtimeContextBudget(ITianshuConfig config) {
        return config.getLlmAXDynamicRagChatTokenBudget();
    }
}
