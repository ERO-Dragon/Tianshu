package com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval;

public record AXMemoryRetrievalPolicy(
        double l1ClusterThreshold,
        double l2EffectiveMappingThreshold,
        int l1ClusterMaxSize,
        int l2EffectiveMappingMaxSize,
        int maxRoutedCandidates,
        int minRoutedL1Clusters,
        double l1RouteScoreFloor,
        double directEntityBoost,
        double relatedEntityBoost,
        double maxTimeDecay,
        double timeDecayDaysToMax,
        double spatialNearDistance,
        double spatialMidDistance,
        double spatialFarDistance,
        double spatialMidWeight,
        double spatialFarWeight,
        double spatialVeryFarWeight,
        double chainExpansionScoreRatio,
        double chainExpansionMinScore,
        int chainExpansionRadius,
        double hotScoreThreshold,
        double warmScoreThreshold,
        double coldScoreThreshold,
        double hotBudgetRatio,
        double warmBudgetRatio
) {
    public static final AXMemoryRetrievalPolicy DEFAULT = new AXMemoryRetrievalPolicy(
            0.56D,
            0.92D,
            256,
            48,
            4096,
            4,
            0.20D,
            1.25D,
            1.08D,
            0.10D,
            3650.0D,
            64.0D,
            256.0D,
            1024.0D,
            0.96D,
            0.88D,
            0.76D,
            0.82D,
            0.35D,
            1,
            0.60D,
            0.35D,
            0.15D,
            0.60D,
            0.30D
    );

    public AXMemoryRetrievalPolicy {
        l1ClusterThreshold = clamp(l1ClusterThreshold, 0.0D, 1.0D);
        l2EffectiveMappingThreshold = clamp(l2EffectiveMappingThreshold, l1ClusterThreshold, 1.0D);
        l1ClusterMaxSize = Math.max(1, l1ClusterMaxSize);
        l2EffectiveMappingMaxSize = Math.max(1, l2EffectiveMappingMaxSize);
        maxRoutedCandidates = Math.max(l1ClusterMaxSize, maxRoutedCandidates);
        minRoutedL1Clusters = Math.max(1, minRoutedL1Clusters);
        l1RouteScoreFloor = Math.max(0.0D, l1RouteScoreFloor);
        directEntityBoost = Math.max(1.0D, directEntityBoost);
        relatedEntityBoost = Math.max(1.0D, relatedEntityBoost);
        maxTimeDecay = clamp(maxTimeDecay, 0.0D, 0.9D);
        timeDecayDaysToMax = Math.max(1.0D, timeDecayDaysToMax);
        spatialNearDistance = Math.max(0.0D, spatialNearDistance);
        spatialMidDistance = Math.max(spatialNearDistance, spatialMidDistance);
        spatialFarDistance = Math.max(spatialMidDistance, spatialFarDistance);
        spatialMidWeight = clamp(spatialMidWeight, 0.0D, 1.0D);
        spatialFarWeight = clamp(spatialFarWeight, 0.0D, spatialMidWeight);
        spatialVeryFarWeight = clamp(spatialVeryFarWeight, 0.0D, spatialFarWeight);
        chainExpansionScoreRatio = clamp(chainExpansionScoreRatio, 0.0D, 1.0D);
        chainExpansionMinScore = Math.max(0.0D, chainExpansionMinScore);
        chainExpansionRadius = Math.max(0, chainExpansionRadius);
        hotScoreThreshold = Math.max(0.0D, hotScoreThreshold);
        warmScoreThreshold = clamp(warmScoreThreshold, 0.0D, hotScoreThreshold);
        coldScoreThreshold = clamp(coldScoreThreshold, 0.0D, warmScoreThreshold);
        hotBudgetRatio = clamp(hotBudgetRatio, 0.0D, 1.0D);
        warmBudgetRatio = clamp(warmBudgetRatio, 0.0D, 1.0D - hotBudgetRatio);
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
