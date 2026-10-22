package com.rheinmetal.tianshu.function.auxilium.core.context;

/**
 * Coarse context tier derived from {@link AXMemoryWindowPolicy#totalContextTokenBudget()}.
 *
 * <p>A tier is a knob, not a measurement: it only widens or narrows breadth-sensitive behaviour
 * (memory chain expansion today). It never selects the system prompt and never reshapes the prompt
 * layout. The upper bounds are stated in tokens so every consumer can cite the same numbers.</p>
 */
public enum AXContextTier {
    /** Up to 32K tokens. The pre-tier behaviour lives here, so it is the conservative default. */
    COMPACT(32_768),
    /** Up to 100K tokens. */
    STANDARD(102_400),
    /** Up to 200K tokens. */
    EXTENDED(204_800),
    /** Anything above 200K tokens. */
    LARGE(Integer.MAX_VALUE);

    private final int maxTokens;

    AXContextTier(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    /** Inclusive upper bound of this tier in context tokens. */
    public int maxTokens() {
        return maxTokens;
    }

    /**
     * Tier of a context budget.
     *
     * <p>Unknown, zero and negative budgets resolve to {@link #COMPACT}: an unresolvable budget must
     * behave like the most conservative tier, never like the most permissive one.</p>
     */
    public static AXContextTier of(int contextTokenBudget) {
        for (AXContextTier tier : values()) {
            if (contextTokenBudget <= tier.maxTokens) {
                return tier;
            }
        }
        return LARGE;
    }
}
