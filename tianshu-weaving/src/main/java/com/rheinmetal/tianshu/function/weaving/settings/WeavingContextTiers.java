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
    public static final int TIER_1M = 1_048_576;

    private static final int[] TIERS = {TIER_50K, TIER_100K, TIER_200K, TIER_500K, TIER_1M};

    private WeavingContextTiers() {
    }

    /**
     * The window a model without a declared capacity starts from.
     *
     * <p>It is a declaration by this build, not the provider's answer: every model the current cloud
     * line-up offers is at least this wide, so a model the picker adds or a hand-typed id gets a
     * budget that is useful without overstating an unknown endpoint. A model with a smaller real
     * window is corrected by the player, or by the preset catalog when it lists that model.</p>
     */
    public static int defaultWindow() {
        return TIER_200K;
    }

    /** Whether {@code windowTokens} is one of the selectable tiers. */
    public static boolean isTier(int windowTokens) {
        for (int tier : TIERS) {
            if (tier == windowTokens) {
                return true;
            }
        }
        return false;
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

    /**
     * Whether a declared window is storable: not below the smallest tier, and not above the storage
     * bound. The one definition used by the settings form and by save-time validation.
     */
    public static boolean isValidWindow(int windowTokens) {
        return windowTokens >= minimumTier()
                && windowTokens <= WeavingConfiguration.MAX_LLM_CONTEXT_WINDOW_TOKENS;
    }
}
