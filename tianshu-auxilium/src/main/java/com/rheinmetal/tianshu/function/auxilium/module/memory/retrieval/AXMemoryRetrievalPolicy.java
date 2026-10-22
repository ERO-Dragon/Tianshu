package com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval;

import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextTier;

/**
 * Retrieval policy.
 *
 * <p>Most numbers are budget ratios and score cutoffs; they do not depend on the context tier.
 * Chain expansion is the exception: how far the retriever may follow the STM chain away from a
 * confident hit is a breadth decision, and breadth is what a larger context buys. That pair
 * ({@code chainExpansionRadius} in linked hops, {@code chainExpansionBreadth} in neighbour blocks) is
 * therefore resolved per request through {@link #forTier(AXContextTier)}; see
 * {@code AXMemoryRetriever} for the walk itself.</p>
 *
 * <h2>Score cutoffs are relative, never absolute</h2>
 *
 * <p>A tier cutoff is a pair {@code (floor, ratio)} resolved against the score distribution of the
 * current request:</p>
 *
 * <pre>
 * hot  = max(HOT_FLOOR,  HOT_RATIO  × topScore)
 * warm = max(WARM_FLOOR, WARM_RATIO × topScore)
 * cold = COLD_FLOOR
 * </pre>
 *
 * <p>The scores here are products (similarity × time decay × importance), so they drift with the
 * whole result set: a weak set of candidates scores 0.10–0.20 in absolute terms, a strong one
 * 0.70–0.90. A fixed absolute cutoff therefore misbehaves in exactly the two cases that matter —
 * when every hit is weak it pushes the entire set down to cold, and when every hit is strong it
 * pushes the entire set into hot. A line relative to {@code topScore} keeps the tiering meaningful
 * regardless of the distribution, and guarantees "the single most relevant candidate is hot"
 * whenever {@code topScore} clears its floor.</p>
 *
 * <p>The floor is the only absolute part left, and it exists for the one failure mode a relative line
 * has: a set where everything is garbage but the best garbage is relatively high ({@code topScore}
 * of 0.06 would put a 0.043 line into hot). Cold has no ratio at all — it is the "worth showing at
 * all" floor, not a tier of its own.</p>
 */
public record AXMemoryRetrievalPolicy(
        double l1ClusterThreshold,
        double l2EffectiveMappingThreshold,
        int l1ClusterMaxSize,
        int l2EffectiveMappingMaxSize,
        int minRoutedL1Clusters,
        double chainExpansionScoreRatio,
        double chainExpansionMinScore,
        int chainExpansionRadius,
        int chainExpansionBreadth,
        double hotScoreRatio,
        double warmScoreRatio,
        double hotScoreFloor,
        double warmScoreFloor,
        double coldScoreFloor,
        double hotBudgetRatio,
        double warmBudgetRatio
) {
    /** Hot tier runs at {@code HOT_RATIO × topScore}: an importance step of at most ~2 inside one cluster. */
    public static final double HOT_RATIO = 0.72D;

    /** Warm tier runs at {@code WARM_RATIO × topScore}: one to two recency half-lives below the top. */
    public static final double WARM_RATIO = 0.45D;

    /** Absolute floor of the hot line; keeps a uniformly weak result set from promoting its best garbage. */
    public static final double HOT_FLOOR = 0.22D;

    /** Absolute floor of the warm line; same purpose, one step down. */
    public static final double WARM_FLOOR = 0.08D;

    /** Cold is a floor only: below it a candidate is not worth a slot at all. */
    public static final double COLD_FLOOR = 0.02D;

    // Chain expansion per context tier. Radius is measured in linked hops along previousStmId /
    // nextStmId; breadth is the number of neighbour blocks one anchor may pull in, so it is twice the
    // radius for a linear chain (one predecessor plus one successor per hop).
    /** Compact tier: one hop each way — one predecessor plus one successor. The pre-tier behaviour. */
    static final int CHAIN_RADIUS_COMPACT = 1;
    static final int CHAIN_BREADTH_COMPACT = 2;

    /** Standard tier: two hops each way. */
    static final int CHAIN_RADIUS_STANDARD = 2;
    static final int CHAIN_BREADTH_STANDARD = 4;

    /** Extended tier: three hops each way. */
    static final int CHAIN_RADIUS_EXTENDED = 3;
    static final int CHAIN_BREADTH_EXTENDED = 6;

    /** Large tier: four hops each way. Beyond this the token budget, not the walk, is the limit. */
    static final int CHAIN_RADIUS_LARGE = 4;
    static final int CHAIN_BREADTH_LARGE = 8;

    public static final AXMemoryRetrievalPolicy DEFAULT = new AXMemoryRetrievalPolicy(
            0.56D,
            0.92D,
            256,
            48,
            4,
            0.82D,
            0.35D,
            CHAIN_RADIUS_COMPACT,
            CHAIN_BREADTH_COMPACT,
            HOT_RATIO,
            WARM_RATIO,
            HOT_FLOOR,
            WARM_FLOOR,
            COLD_FLOOR,
            0.60D,
            0.30D
    );

    public AXMemoryRetrievalPolicy {
        l1ClusterThreshold = clamp(l1ClusterThreshold, 0.0D, 1.0D);
        l2EffectiveMappingThreshold = clamp(l2EffectiveMappingThreshold, l1ClusterThreshold, 1.0D);
        l1ClusterMaxSize = Math.max(1, l1ClusterMaxSize);
        l2EffectiveMappingMaxSize = Math.max(1, l2EffectiveMappingMaxSize);
        minRoutedL1Clusters = Math.max(1, minRoutedL1Clusters);
        chainExpansionScoreRatio = clamp(chainExpansionScoreRatio, 0.0D, 1.0D);
        chainExpansionMinScore = Math.max(0.0D, chainExpansionMinScore);
        chainExpansionRadius = Math.max(0, chainExpansionRadius);
        chainExpansionBreadth = Math.max(0, chainExpansionBreadth);
        // The order hot ≥ warm ≥ cold has to survive the clamps, otherwise a misconfigured policy would
        // silently swap the tiers instead of failing loudly.
        hotScoreRatio = clamp(hotScoreRatio, 0.0D, 1.0D);
        warmScoreRatio = clamp(warmScoreRatio, 0.0D, hotScoreRatio);
        hotScoreFloor = Math.max(0.0D, hotScoreFloor);
        warmScoreFloor = clamp(warmScoreFloor, 0.0D, hotScoreFloor);
        coldScoreFloor = clamp(coldScoreFloor, 0.0D, warmScoreFloor);
        hotBudgetRatio = clamp(hotBudgetRatio, 0.0D, 1.0D);
        warmBudgetRatio = clamp(warmBudgetRatio, 0.0D, 1.0D - hotBudgetRatio);
    }

    /**
     * Hot cutoff for a request whose best candidate scores {@code topScore}: {@code max(floor, ratio × top)}.
     */
    public double hotScoreCutoff(double topScore) {
        return Math.max(hotScoreFloor, hotScoreRatio * Math.max(0.0D, topScore));
    }

    /**
     * Warm cutoff for a request whose best candidate scores {@code topScore}: {@code max(floor, ratio × top)}.
     */
    public double warmScoreCutoff(double topScore) {
        return Math.max(warmScoreFloor, warmScoreRatio * Math.max(0.0D, topScore));
    }

    /** Cold cutoff: a floor, deliberately independent of {@code topScore}. */
    public double coldScoreCutoff() {
        return coldScoreFloor;
    }

    /**
     * This policy with its chain expansion replaced by the values of {@code tier}.
     *
     * <p>A null or unknown tier yields {@link AXContextTier#COMPACT}, so an unresolved budget behaves
     * exactly like the compact tier instead of widening the walk by accident.</p>
     */
    public AXMemoryRetrievalPolicy forTier(AXContextTier tier) {
        AXContextTier effectiveTier = tier == null ? AXContextTier.COMPACT : tier;
        int radius = chainRadiusFor(effectiveTier);
        int breadth = chainBreadthFor(effectiveTier);
        if (radius == chainExpansionRadius && breadth == chainExpansionBreadth) {
            return this;
        }
        return withChainExpansion(radius, breadth);
    }

    private AXMemoryRetrievalPolicy withChainExpansion(int radius, int breadth) {
        return new AXMemoryRetrievalPolicy(
                l1ClusterThreshold,
                l2EffectiveMappingThreshold,
                l1ClusterMaxSize,
                l2EffectiveMappingMaxSize,
                minRoutedL1Clusters,
                chainExpansionScoreRatio,
                chainExpansionMinScore,
                radius,
                breadth,
                hotScoreRatio,
                warmScoreRatio,
                hotScoreFloor,
                warmScoreFloor,
                coldScoreFloor,
                hotBudgetRatio,
                warmBudgetRatio
        );
    }

    private static int chainRadiusFor(AXContextTier tier) {
        return switch (tier) {
            case COMPACT -> CHAIN_RADIUS_COMPACT;
            case STANDARD -> CHAIN_RADIUS_STANDARD;
            case EXTENDED -> CHAIN_RADIUS_EXTENDED;
            case LARGE -> CHAIN_RADIUS_LARGE;
        };
    }

    private static int chainBreadthFor(AXContextTier tier) {
        return switch (tier) {
            case COMPACT -> CHAIN_BREADTH_COMPACT;
            case STANDARD -> CHAIN_BREADTH_STANDARD;
            case EXTENDED -> CHAIN_BREADTH_EXTENDED;
            case LARGE -> CHAIN_BREADTH_LARGE;
        };
    }

    public double coldBudgetRatio() {
        return Math.max(0.0D, 1.0D - hotBudgetRatio - warmBudgetRatio);
    }

    public int hotBlockBudget(int maxBlocks) {
        return Math.min(maxBlocks, (int) Math.round(maxBlocks * hotBudgetRatio));
    }

    public int warmBlockBudget(int maxBlocks) {
        return Math.min(maxBlocks - hotBlockBudget(maxBlocks), (int) Math.round(maxBlocks * warmBudgetRatio));
    }

    public int coldBlockBudget(int maxBlocks) {
        return Math.max(0, maxBlocks - hotBlockBudget(maxBlocks) - warmBlockBudget(maxBlocks));
    }

    public int hotTokenBudget(int tokenBudget) {
        return tokenBudget <= 0 ? 0 : (int) Math.round(tokenBudget * hotBudgetRatio);
    }

    public int warmTokenBudget(int tokenBudget) {
        return tokenBudget <= 0 ? 0 : (int) Math.round(tokenBudget * warmBudgetRatio);
    }

    public int coldTokenBudget(int tokenBudget) {
        if (tokenBudget <= 0) {
            return 0;
        }
        return Math.max(0, tokenBudget - hotTokenBudget(tokenBudget) - warmTokenBudget(tokenBudget));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
