package com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval;

import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextTier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXMemoryRetrievalPolicyTest {

    @Test
    void defaultBudgetRatiosSumToOne() {
        AXMemoryRetrievalPolicy policy = AXMemoryRetrievalPolicy.DEFAULT;
        double total = policy.hotBudgetRatio() + policy.warmBudgetRatio() + policy.coldBudgetRatio();
        assertEquals(1.0D, total, 1e-9, "Hot + Warm + Cold 比例之和应为 1");
    }

    @Test
    void defaultBlockBudgetsSumToMaxBlocks() {
        AXMemoryRetrievalPolicy policy = AXMemoryRetrievalPolicy.DEFAULT;
        int maxBlocks = 10;
        int total = policy.hotBlockBudget(maxBlocks) + policy.warmBlockBudget(maxBlocks) + policy.coldBlockBudget(maxBlocks);
        assertEquals(maxBlocks, total, "三层 block 预算之和应等于 maxBlocks");
    }

    @Test
    void defaultTokenBudgetsSumToTokenBudget() {
        AXMemoryRetrievalPolicy policy = AXMemoryRetrievalPolicy.DEFAULT;
        int tokenBudget = 1000;
        int total = policy.hotTokenBudget(tokenBudget) + policy.warmTokenBudget(tokenBudget) + policy.coldTokenBudget(tokenBudget);
        assertEquals(tokenBudget, total, "三层 token 预算之和应等于 tokenBudget");
    }

    @Test
    void hotGetsLargestShareByDefault() {
        AXMemoryRetrievalPolicy policy = AXMemoryRetrievalPolicy.DEFAULT;
        int maxBlocks = 10;
        assertTrue(policy.hotBlockBudget(maxBlocks) >= policy.warmBlockBudget(maxBlocks), "Hot 层 block 预算应不小于 Warm 层");
        assertTrue(policy.warmBlockBudget(maxBlocks) >= policy.coldBlockBudget(maxBlocks), "Warm 层 block 预算应不小于 Cold 层");
    }

    @Test
    void zeroTokenBudgetProducesZeroTierTokenBudgets() {
        AXMemoryRetrievalPolicy policy = AXMemoryRetrievalPolicy.DEFAULT;
        assertEquals(0, policy.hotTokenBudget(0));
        assertEquals(0, policy.warmTokenBudget(0));
        assertEquals(0, policy.coldTokenBudget(0));
    }

    @Test
    void cutoffsAreRelativeToTheRequestTopScore() {
        AXMemoryRetrievalPolicy policy = AXMemoryRetrievalPolicy.DEFAULT;
        assertEquals(AXMemoryRetrievalPolicy.HOT_RATIO, policy.hotScoreCutoff(1.0D), 1e-9, "强集合下 Hot 线 = HOT_RATIO × top");
        assertEquals(AXMemoryRetrievalPolicy.WARM_RATIO, policy.warmScoreCutoff(1.0D), 1e-9, "强集合下 Warm 线 = WARM_RATIO × top");
        assertEquals(AXMemoryRetrievalPolicy.COLD_FLOOR, policy.coldScoreCutoff(), 1e-9, "Cold 只有绝对地板");
    }

    @Test
    void weakResultSetsFallBackToTheAbsoluteFloors() {
        AXMemoryRetrievalPolicy policy = AXMemoryRetrievalPolicy.DEFAULT;
        assertEquals(AXMemoryRetrievalPolicy.HOT_FLOOR, policy.hotScoreCutoff(0.10D), 1e-9, "弱集合下 Hot 线落回地板");
        assertEquals(AXMemoryRetrievalPolicy.WARM_FLOOR, policy.warmScoreCutoff(0.10D), 1e-9, "弱集合下 Warm 线落回地板");
    }

    @Test
    void theTopCandidateAlwaysReachesHotOnceItClearsItsFloor() {
        AXMemoryRetrievalPolicy policy = AXMemoryRetrievalPolicy.DEFAULT;
        for (double topScore : new double[]{AXMemoryRetrievalPolicy.HOT_FLOOR, 0.30D, 0.55D, 0.90D}) {
            assertTrue(policy.hotScoreCutoff(topScore) <= topScore, "最相关的候选必须能进 Hot: top=" + topScore);
        }
    }

    @Test
    void cutoffsStayOrderedHotThenWarmThenCold() {
        AXMemoryRetrievalPolicy policy = AXMemoryRetrievalPolicy.DEFAULT;
        for (double topScore : new double[]{0.0D, 0.10D, 0.35D, 1.0D}) {
            assertTrue(policy.hotScoreCutoff(topScore) >= policy.warmScoreCutoff(topScore),
                    "Hot 线应不小于 Warm 线: top=" + topScore);
            assertTrue(policy.warmScoreCutoff(topScore) > policy.coldScoreCutoff(),
                    "Warm 线应大于 Cold 线: top=" + topScore);
        }
    }

    @Test
    void scoreThresholdsAndBudgetRatiosAreClampedIndependently() {
        AXMemoryRetrievalPolicy policy = new AXMemoryRetrievalPolicy(
                0.56D, 0.92D, 256, 48, 4,
                0.82D, 0.35D, 1, 2,
                0.20D, 0.90D,
                0.05D, 0.50D, 0.60D,
                0.90D, 0.50D
        );
        assertTrue(policy.hotScoreRatio() >= policy.warmScoreRatio(), "Warm 比例应被夹到不超 Hot 比例");
        assertTrue(policy.hotScoreFloor() >= policy.warmScoreFloor(), "Warm 地板应被夹到不超 Hot 地板");
        assertTrue(policy.warmScoreFloor() >= policy.coldScoreFloor(), "Cold 地板应被夹到不超 Warm 地板");
        assertTrue(policy.hotBudgetRatio() + policy.warmBudgetRatio() <= 1.0D, "Hot + Warm 比例之和应不超 1");
        assertTrue(policy.coldBudgetRatio() >= 0.0D, "Cold 比例应为非负");
    }

    @Test
    void compactTierKeepsThePreTierChainExpansion() {
        AXMemoryRetrievalPolicy policy = AXMemoryRetrievalPolicy.DEFAULT.forTier(AXContextTier.COMPACT);

        assertEquals(AXMemoryRetrievalPolicy.CHAIN_RADIUS_COMPACT, policy.chainExpansionRadius());
        assertEquals(AXMemoryRetrievalPolicy.CHAIN_BREADTH_COMPACT, policy.chainExpansionBreadth(), "紧凑档应为前一条加后一条");
    }

    @Test
    void widerTiersWidenChainExpansionAndLeaveOtherKnobsAlone() {
        AXMemoryRetrievalPolicy standard = AXMemoryRetrievalPolicy.DEFAULT.forTier(AXContextTier.STANDARD);
        AXMemoryRetrievalPolicy extended = AXMemoryRetrievalPolicy.DEFAULT.forTier(AXContextTier.EXTENDED);
        AXMemoryRetrievalPolicy large = AXMemoryRetrievalPolicy.DEFAULT.forTier(AXContextTier.LARGE);

        assertTrue(standard.chainExpansionRadius() > AXMemoryRetrievalPolicy.CHAIN_RADIUS_COMPACT);
        assertTrue(extended.chainExpansionRadius() > standard.chainExpansionRadius());
        assertTrue(large.chainExpansionRadius() >= extended.chainExpansionRadius());
        assertEquals(standard.hotScoreCutoff(1.0D), AXMemoryRetrievalPolicy.DEFAULT.hotScoreCutoff(1.0D), "档位只改链式发散");
        assertEquals(standard.hotBudgetRatio(), AXMemoryRetrievalPolicy.DEFAULT.hotBudgetRatio(), "档位只改链式发散");
    }

    @Test
    void unknownTierBehavesLikeTheCompactTier() {
        AXMemoryRetrievalPolicy unresolved = AXMemoryRetrievalPolicy.DEFAULT.forTier(null);

        assertEquals(AXContextTier.COMPACT, AXContextTier.of(0), "未知预算必须落到最保守档");
        assertEquals(AXMemoryRetrievalPolicy.CHAIN_BREADTH_COMPACT, unresolved.chainExpansionBreadth());
    }
}
