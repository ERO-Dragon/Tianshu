package com.rheinmetal.tianshu.function.auxilium.core.context;

import com.rheinmetal.tianshu.api.ITianshuConfig;

/**
 * Auxilium memory window policy.
 *
 * <p>Token budgets are derived from a single {@code contextTokenBudget} total.
 * This object is only the baseline layout; per-turn selection can still trim or skip
 * low-value candidates according to the current model and request density.</p>
 *
 * <p>Reference budget is 8000 tokens; all ratio constants and baseline compression parameters
 * are calibrated against that baseline. At runtime, actual values equal
 * {@code contextTokenBudget * ratio}.</p>
 */
public record AXMemoryWindowPolicy(
        int chatInputTokenBudget,
        int recentRawChatTokenBudget,
        int shortTermChatTokenBudget,
        int memoryRagTokenBudget,
        int staticContentTokenBudget,
        int dynamicContentTokenBudget,
        int recentRawKeepTokenTarget,
        int recentRawKeepTokenMax,
        int shortTermCompressTokenTarget,
        int shortTermCompressTokenMax,
        int maxRawEstimatedTokens,
        int maxRawCharacters,
        int shortTermChatBlockLimit,
        long conversationPauseMillis
) {
    /** Reference total budget used to calibrate ratios and compression baselines. */
    static final int REFERENCE_BUDGET = 8000;

    // Baseline input-slot ratios. They are section-aligned defaults, not prompt sub-block names.
    static final double RATIO_RECENT_RAW = 0.25;
    static final double RATIO_SHORT_TERM = 0.25;
    static final double RATIO_MEMORY_RAG = 0.1875;
    static final double RATIO_STATIC_CONTENT = 0.125;
    static final double RATIO_DYNAMIC_CONTENT = 0.125;

    // Compression baselines at reference budget (8000). Scaled linearly at runtime.
    static final int BASE_RECENT_RAW_KEEP_TARGET = 5000;
    static final int BASE_RECENT_RAW_KEEP_MAX = 8000;
    static final int BASE_SHORT_TERM_COMPRESS_TARGET = 7000;
    static final int BASE_SHORT_TERM_COMPRESS_MAX = 10000;
    static final int BASE_MAX_RAW_ESTIMATED_TOKENS = 28000;
    static final int BASE_MAX_RAW_CHARACTERS = 120000;

    public static final AXMemoryWindowPolicy DEFAULT = fromBudget(REFERENCE_BUDGET, 3, 60000L);

    public AXMemoryWindowPolicy {
        chatInputTokenBudget = Math.max(1000, chatInputTokenBudget);
        recentRawChatTokenBudget = Math.max(0, recentRawChatTokenBudget);
        shortTermChatTokenBudget = Math.max(0, shortTermChatTokenBudget);
        memoryRagTokenBudget = Math.max(0, memoryRagTokenBudget);
        staticContentTokenBudget = Math.max(0, staticContentTokenBudget);
        dynamicContentTokenBudget = Math.max(0, dynamicContentTokenBudget);
        recentRawKeepTokenTarget = Math.max(0, recentRawKeepTokenTarget);
        recentRawKeepTokenMax = Math.max(recentRawKeepTokenTarget, recentRawKeepTokenMax);
        shortTermCompressTokenTarget = Math.max(0, shortTermCompressTokenTarget);
        shortTermCompressTokenMax = Math.max(shortTermCompressTokenTarget, shortTermCompressTokenMax);
        maxRawEstimatedTokens = Math.max(recentRawKeepTokenMax + shortTermCompressTokenMax, maxRawEstimatedTokens);
        maxRawCharacters = Math.max(0, maxRawCharacters);
        shortTermChatBlockLimit = Math.max(0, shortTermChatBlockLimit);
        conversationPauseMillis = Math.max(0L, conversationPauseMillis);
    }

    /**
     * Build a policy from the LLM-provided context token budget. All input slots and compression
     * parameters are scaled proportionally.
     */
    public static AXMemoryWindowPolicy fromBudget(int contextTokenBudget, int shortTermChatBlockLimit, long conversationPauseMillis) {
        int budget = Math.max(1000, contextTokenBudget);
        double scale = (double) budget / REFERENCE_BUDGET;
        return new AXMemoryWindowPolicy(
                budget,
                (int) (budget * RATIO_RECENT_RAW),
                (int) (budget * RATIO_SHORT_TERM),
                (int) (budget * RATIO_MEMORY_RAG),
                (int) (budget * RATIO_STATIC_CONTENT),
                (int) (budget * RATIO_DYNAMIC_CONTENT),
                Math.max(1, (int) (BASE_RECENT_RAW_KEEP_TARGET * scale)),
                Math.max(1, (int) (BASE_RECENT_RAW_KEEP_MAX * scale)),
                Math.max(1, (int) (BASE_SHORT_TERM_COMPRESS_TARGET * scale)),
                Math.max(1, (int) (BASE_SHORT_TERM_COMPRESS_MAX * scale)),
                Math.max(1, (int) (BASE_MAX_RAW_ESTIMATED_TOKENS * scale)),
                Math.max(1, (int) (BASE_MAX_RAW_CHARACTERS * scale)),
                shortTermChatBlockLimit,
                conversationPauseMillis
        );
    }

    public static AXMemoryWindowPolicy fromConfig(ITianshuConfig config) {
        if (config == null) {
            return DEFAULT;
        }
        return fromBudget(
                config.getLlmPromptTokenBudget(),
                config.getLlmAXShortTermChatBlockLimit(),
                config.getLlmAXConversationPauseMillis()
        );
    }
}
