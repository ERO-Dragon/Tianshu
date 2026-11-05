package com.rheinmetal.tianshu.function.weaving.settings;

/**
 * The context capacities a cloud model may declare.
 *
 * <p>A tier is the model's own context capacity, not a separate budget knob: the value a model entry
 * declares is what prompt budgeting derives from, and these constants are the canonical choices and
 * the validation floor. A provider with a window between two tiers simply stores its own number.</p>
 */
public final class WeavingContextTiers {
    public static final int TIER_50K = 51_200;
    public static final int TIER_100K = 102_400;
    public static final int TIER_200K = 204_800;
    public static final int TIER_500K = 512_000;

    private static final int[] TIERS = {TIER_50K, TIER_100K, TIER_200K, TIER_500K};

    private WeavingContextTiers() {
    }

    /** All tiers, smallest first. A copy, so callers cannot mutate the policy. */
    public static int[] values() {
        return TIERS.clone();
    }

    /**
     * The smallest selectable capacity.
     *
     * <p>A declared window below this fits no tier at all, so callers that validate a configured
     * window check it against this value.
     */
    public static int minimumTier() {
        return TIERS[0];
    }

    /** Whether {@code tier} fits inside the declared model window. */
    public static boolean supportsTier(int tier, int contextWindowTokens) {
        return tier > 0 && contextWindowTokens > 0 && tier <= contextWindowTokens;
    }
}
