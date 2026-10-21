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

    /** Completion tokens reserved out of the window; the prompt may never spend the whole budget. */
    private static final int OUTPUT_RESERVE_TOKENS = 512;

    /** Headroom absorbs token-estimation error; it is a fixed fraction of the prompt window. */
    private static final double HEADROOM_RATIO = 0.0625D;

    private static final int HEADROOM_MIN_TOKENS = 4_096;
    private static final int HEADROOM_MAX_TOKENS = 32_768;
    private static final int HEADROOM_GRANULARITY = 1_024;

    /**
     * Single source of truth for the cloud budget. A cloud endpoint never reports its window, so the
     * declared window is the only input: prompt budget = window − output reserve − headroom.
     *
     * @param windowTokens declared context window, in tokens
     */
    public static LlmContextBudgetSnapshot ofConfiguredWindow(int windowTokens) {
        if (windowTokens <= 0) {
            return unavailable("LLM_CONTEXT_WINDOW_NOT_CONFIGURED");
        }
        int promptWindow = Math.max(0, windowTokens - OUTPUT_RESERVE_TOKENS);
        long rawHeadroom = (long) Math.ceil(HEADROOM_RATIO * promptWindow);
        int headroom = (int) Math.min(HEADROOM_MAX_TOKENS, Math.max(HEADROOM_MIN_TOKENS, rawHeadroom));
        headroom = ceilToGranularity(headroom, HEADROOM_GRANULARITY);
        int promptTokenBudget = Math.max(0, windowTokens - OUTPUT_RESERVE_TOKENS - headroom);
        return new LlmContextBudgetSnapshot(
                windowTokens,
                windowTokens,
                windowTokens,
                windowTokens,
                promptTokenBudget,
                headroom,
                0L,
                true,
                ""
        );
    }

    private static int ceilToGranularity(int value, int granularity) {
        return ((value + granularity - 1) / granularity) * granularity;
    }
}
