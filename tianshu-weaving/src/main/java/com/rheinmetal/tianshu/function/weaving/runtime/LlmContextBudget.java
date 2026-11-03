package com.rheinmetal.tianshu.function.weaving.runtime;

/**
 * The only place the cloud prompt budget is computed.
 *
 * <p>A cloud endpoint never reports its window, so the declared model capacity is the single input:
 * prompt budget = capacity − output reserve − headroom. The headroom absorbs token-estimation error
 * and is a fixed fraction of the prompt window, clamped and rounded up to a granularity so the
 * number does not wobble between model switches.</p>
 */
public final class LlmContextBudget {

    /** Completion tokens reserved out of the window; the prompt may never spend the whole budget. */
    private static final int OUTPUT_RESERVE_TOKENS = 512;

    /** Headroom absorbs token-estimation error; it is a fixed fraction of the prompt window. */
    private static final double HEADROOM_RATIO = 0.0625D;

    private static final int HEADROOM_MIN_TOKENS = 4_096;
    private static final int HEADROOM_MAX_TOKENS = 32_768;
    private static final int HEADROOM_GRANULARITY = 1_024;

    private LlmContextBudget() {
    }

    /**
     * @param windowTokens declared context capacity of the selected model, in tokens
     * @return prompt token budget, or 0 when no capacity is configured
     */
    public static int promptTokenBudget(int windowTokens) {
        if (windowTokens <= 0) {
            return 0;
        }
        int promptWindow = Math.max(0, windowTokens - OUTPUT_RESERVE_TOKENS);
        long rawHeadroom = (long) Math.ceil(HEADROOM_RATIO * promptWindow);
        int headroom = (int) Math.min(HEADROOM_MAX_TOKENS, Math.max(HEADROOM_MIN_TOKENS, rawHeadroom));
        headroom = ceilToGranularity(headroom, HEADROOM_GRANULARITY);
        return Math.max(0, windowTokens - OUTPUT_RESERVE_TOKENS - headroom);
    }

    private static int ceilToGranularity(int value, int granularity) {
        return ((value + granularity - 1) / granularity) * granularity;
    }
}
