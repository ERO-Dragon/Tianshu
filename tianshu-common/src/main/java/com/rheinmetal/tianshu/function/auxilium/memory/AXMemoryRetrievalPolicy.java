package com.rheinmetal.tianshu.function.auxilium.memory;

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
        int chainExpansionRadius
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
            1
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
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
