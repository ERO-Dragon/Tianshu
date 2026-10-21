package com.rheinmetal.tianshu.function.llm.settings;

/**
 * Selectable context-window tiers.
 *
 * <p>A tier is a budget knob only: it decides how much history the assistant may keep. It never
 * changes the system prompt, and it is deliberately independent of the declared model window —
 * which tiers are physically usable is answered by {@link #supportsTier(int, int)}.
 */
public final class LlmContextTiers {
    public static final int TIER_50K = 51_200;
    public static final int TIER_100K = 102_400;
    public static final int TIER_200K = 204_800;
    public static final int TIER_500K = 512_000;

    public static final int DEFAULT_TIER = TIER_100K;

    private static final int[] TIERS = {TIER_50K, TIER_100K, TIER_200K, TIER_500K};

    private LlmContextTiers() {
    }

    /** All tiers, smallest first. A copy, so callers cannot mutate the policy. */
    public static int[] values() {
        return TIERS.clone();
    }

    /** Whether {@code tier} fits inside the declared model window. */
    public static boolean supportsTier(int tier, int contextWindowTokens) {
        return tier > 0 && contextWindowTokens > 0 && tier <= contextWindowTokens;
    }

    /**
     * Largest supported tier that does not exceed {@code desiredTier}.
     *
     * <p>Used when the declared window shrinks (model switch): a tier that no longer fits degrades
     * to the closest one that does. Falls back to {@link #DEFAULT_TIER} when nothing fits, so the
     * caller never has to special-case an unusable configuration.
     */
    public static int downgradeToLargestAvailable(int desiredTier, int contextWindowTokens) {
        for (int index = TIERS.length - 1; index >= 0; index--) {
            int candidate = TIERS[index];
            if (candidate <= desiredTier && supportsTier(candidate, contextWindowTokens)) {
                return candidate;
            }
        }
        for (int index = TIERS.length - 1; index >= 0; index--) {
            if (supportsTier(TIERS[index], contextWindowTokens)) {
                return TIERS[index];
            }
        }
        return DEFAULT_TIER;
    }
}
