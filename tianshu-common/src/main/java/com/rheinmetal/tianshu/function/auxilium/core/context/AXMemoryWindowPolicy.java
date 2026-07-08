package com.rheinmetal.tianshu.function.auxilium.core.context;

import com.rheinmetal.tianshu.api.ITianshuConfig;

/**
 * Auxilium memory window policy.
 *
 * <p>Token budgets are derived from a single LLM {@code contextTokenBudget} total.
 * CHAT and TASK each get their own input layout based on that total; this does not
 * split the underlying LLM ctx into two physical windows.</p>
 *
 * <p>{@code totalContextTokenBudget} is the per-request total upper bound for prompt input plus
 * model output. AX intentionally uses only part of it as the default CHAT/TASK input target, so
 * output and runtime safety still have room.</p>
 */
public record AXMemoryWindowPolicy(
        int totalContextTokenBudget,
        int chatInputTokenBudget,
        int chatOutputReserveTokenBudget,
        int chatSystemTokenBudget,
        int knowledgeRagTokenBudget,
        int retrievedMemoryTokenBudget,
        int recentMemoryTokenBudget,
        int recentRawDialogueTokenBudget,
        int currentInputTokenBudget,
        int taskInputTokenBudget,
        int taskOutputReserveTokenBudget,
        int taskSystemTokenBudget,
        int taskInstructionTokenBudget,
        int taskPayloadTokenBudget,
        int recentRawKeepTokenTarget,
        int recentRawKeepTokenMax,
        int shortTermCompressTokenTarget,
        int shortTermCompressTokenMax,
        int maxRawTokenCount,
        int maxRawCharacters,
        int shortTermChatBlockLimit,
        long conversationPauseMillis
) {
    /** Reference total budget used to calibrate ratios and compression baselines. */
    static final int REFERENCE_BUDGET = 8000;

    static final double RATIO_CHAT_INPUT = 0.60;

    // CHAT baseline input-slot ratios. These are relative to chatInputTokenBudget and sum to 1.0.
    static final double RATIO_CHAT_SYSTEM = 0.10;
    static final double RATIO_KNOWLEDGE_RAG = 0.30;
    static final double RATIO_RETRIEVED_MEMORY = 0.25;
    static final double RATIO_RECENT_MEMORY = 0.10;
    static final double RATIO_RECENT_RAW = 0.20;
    static final double RATIO_CURRENT_INPUT = 0.05;

    // TASK baseline input-slot ratios. TASK prompts do not use the CHAT five-block layout.
    static final double RATIO_TASK_SYSTEM = 0.125;
    static final double RATIO_TASK_INSTRUCTION = 0.125;
    static final double RATIO_TASK_PAYLOAD = 0.75;

    // Compression baselines at reference budget (8000). Scaled linearly at runtime.
    static final int BASE_RECENT_RAW_KEEP_TARGET = 5000;
    static final int BASE_RECENT_RAW_KEEP_MAX = 8000;
    static final int BASE_SHORT_TERM_COMPRESS_TARGET = 7000;
    static final int BASE_SHORT_TERM_COMPRESS_MAX = 10000;
    static final int BASE_MAX_RAW_TOKEN_COUNT = 28000;
    static final int BASE_MAX_RAW_CHARACTERS = 120000;

    public static final AXMemoryWindowPolicy DEFAULT = fromBudget(REFERENCE_BUDGET, 3, 60000L);

    public AXMemoryWindowPolicy {
        totalContextTokenBudget = Math.max(1000, totalContextTokenBudget);
        chatInputTokenBudget = Math.max(1000, chatInputTokenBudget);
        chatOutputReserveTokenBudget = Math.max(0, chatOutputReserveTokenBudget);
        chatSystemTokenBudget = Math.max(0, chatSystemTokenBudget);
        knowledgeRagTokenBudget = Math.max(0, knowledgeRagTokenBudget);
        retrievedMemoryTokenBudget = Math.max(0, retrievedMemoryTokenBudget);
        recentMemoryTokenBudget = Math.max(0, recentMemoryTokenBudget);
        recentRawDialogueTokenBudget = Math.max(0, recentRawDialogueTokenBudget);
        currentInputTokenBudget = Math.max(0, currentInputTokenBudget);
        taskInputTokenBudget = Math.max(1000, taskInputTokenBudget);
        taskOutputReserveTokenBudget = Math.max(0, taskOutputReserveTokenBudget);
        taskSystemTokenBudget = Math.max(0, taskSystemTokenBudget);
        taskInstructionTokenBudget = Math.max(0, taskInstructionTokenBudget);
        taskPayloadTokenBudget = Math.max(0, taskPayloadTokenBudget);
        recentRawKeepTokenTarget = Math.max(0, recentRawKeepTokenTarget);
        recentRawKeepTokenMax = Math.max(recentRawKeepTokenTarget, recentRawKeepTokenMax);
        shortTermCompressTokenTarget = Math.max(0, shortTermCompressTokenTarget);
        shortTermCompressTokenMax = Math.max(shortTermCompressTokenTarget, shortTermCompressTokenMax);
        maxRawTokenCount = Math.max(recentRawKeepTokenMax + shortTermCompressTokenMax, maxRawTokenCount);
        maxRawCharacters = Math.max(0, maxRawCharacters);
        shortTermChatBlockLimit = Math.max(0, shortTermChatBlockLimit);
        conversationPauseMillis = Math.max(0L, conversationPauseMillis);
    }

    /**
     * Build a policy from the LLM-provided context token budget. All input slots and compression
     * parameters are scaled proportionally.
     */
    public static AXMemoryWindowPolicy fromBudget(int contextTokenBudget, int shortTermChatBlockLimit, long conversationPauseMillis) {
        int totalBudget = Math.max(1000, contextTokenBudget);
        int chatInputBudget = Math.max(1000, (int) (totalBudget * RATIO_CHAT_INPUT));
        int taskInputBudget = chatInputBudget;
        double scale = (double) totalBudget / REFERENCE_BUDGET;
        return new AXMemoryWindowPolicy(
                totalBudget,
                chatInputBudget,
                Math.max(0, totalBudget - chatInputBudget),
                (int) (chatInputBudget * RATIO_CHAT_SYSTEM),
                (int) (chatInputBudget * RATIO_KNOWLEDGE_RAG),
                (int) (chatInputBudget * RATIO_RETRIEVED_MEMORY),
                (int) (chatInputBudget * RATIO_RECENT_MEMORY),
                (int) (chatInputBudget * RATIO_RECENT_RAW),
                (int) (chatInputBudget * RATIO_CURRENT_INPUT),
                taskInputBudget,
                Math.max(0, totalBudget - taskInputBudget),
                (int) (taskInputBudget * RATIO_TASK_SYSTEM),
                (int) (taskInputBudget * RATIO_TASK_INSTRUCTION),
                (int) (taskInputBudget * RATIO_TASK_PAYLOAD),
                Math.max(1, (int) (BASE_RECENT_RAW_KEEP_TARGET * scale)),
                Math.max(1, (int) (BASE_RECENT_RAW_KEEP_MAX * scale)),
                Math.max(1, (int) (BASE_SHORT_TERM_COMPRESS_TARGET * scale)),
                Math.max(1, (int) (BASE_SHORT_TERM_COMPRESS_MAX * scale)),
                Math.max(1, (int) (BASE_MAX_RAW_TOKEN_COUNT * scale)),
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
