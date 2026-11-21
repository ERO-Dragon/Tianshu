package com.rheinmetal.tianshu.dubhe.module.memory.retrieval;

import com.rheinmetal.tianshu.dubhe.core.context.DubheContextTier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubheMemoryRetrievalPolicyTest {

    @Test
    void defaultBudgetRatiosSumToOne() {
        DubheMemoryRetrievalPolicy policy = DubheMemoryRetrievalPolicy.DEFAULT;
        double total = policy.hotBudgetRatio() + policy.warmBudgetRatio() + policy.coldBudgetRatio();
        assertEquals(1.0D, total, 1e-9, "Hot + Warm + Cold 比例之和应为 1");
    }

    @Test
    void defaultBlockBudgetsSumToMaxBlocks() {
        DubheMemoryRetrievalPolicy policy = DubheMemoryRetrievalPolicy.DEFAULT;
        int maxBlocks = 10;
        int total = policy.hotBlockBudget(maxBlocks) + policy.warmBlockBudget(maxBlocks) + policy.coldBlockBudget(maxBlocks);
        assertEquals(maxBlocks, total, "三层 block 预算之和应等于 maxBlocks");
    }

    @Test
    void defaultTokenBudgetsSumToTokenBudget() {
        DubheMemoryRetrievalPolicy policy = DubheMemoryRetrievalPolicy.DEFAULT;
        int tokenBudget = 1000;
        int total = policy.hotTokenBudget(tokenBudget) + policy.warmTokenBudget(tokenBudget) + policy.coldTokenBudget(tokenBudget);
        assertEquals(tokenBudget, total, "三层 token 预算之和应等于 tokenBudget");
    }

    @Test
    void hotGetsLargestShareByDefault() {
        DubheMemoryRetrievalPolicy policy = DubheMemoryRetrievalPolicy.DEFAULT;
        int maxBlocks = 10;
        assertTrue(policy.hotBlockBudget(maxBlocks) >= policy.warmBlockBudget(maxBlocks), "Hot 层 block 预算应不小于 Warm 层");
        assertTrue(policy.warmBlockBudget(maxBlocks) >= policy.coldBlockBudget(maxBlocks), "Warm 层 block 预算应不小于 Cold 层");
    }

    @Test
    void zeroTokenBudgetProducesZeroTierTokenBudgets() {
        DubheMemoryRetrievalPolicy policy = DubheMemoryRetrievalPolicy.DEFAULT;
        assertEquals(0, policy.hotTokenBudget(0));
        assertEquals(0, policy.warmTokenBudget(0));
        assertEquals(0, policy.coldTokenBudget(0));
    }

    @Test
    void cutoffsAreRelativeToTheRequestTopScore() {
        DubheMemoryRetrievalPolicy policy = DubheMemoryRetrievalPolicy.DEFAULT;
        assertEquals(DubheMemoryRetrievalPolicy.HOT_RATIO, policy.hotScoreCutoff(1.0D), 1e-9, "强集合下 Hot 线 = HOT_RATIO × top");
        assertEquals(DubheMemoryRetrievalPolicy.WARM_RATIO, policy.warmScoreCutoff(1.0D), 1e-9, "强集合下 Warm 线 = WARM_RATIO × top");
        assertEquals(DubheMemoryRetrievalPolicy.COLD_FLOOR, policy.coldScoreCutoff(), 1e-9, "Cold 只有绝对地板");
    }

    @Test
    void weakResultSetsFallBackToTheAbsoluteFloors() {
        DubheMemoryRetrievalPolicy policy = DubheMemoryRetrievalPolicy.DEFAULT;
        assertEquals(DubheMemoryRetrievalPolicy.HOT_FLOOR, policy.hotScoreCutoff(0.10D), 1e-9, "弱集合下 Hot 线落回地板");
        assertEquals(DubheMemoryRetrievalPolicy.WARM_FLOOR, policy.warmScoreCutoff(0.10D), 1e-9, "弱集合下 Warm 线落回地板");
    }

    @Test
    void theTopCandidateAlwaysReachesHotOnceItClearsItsFloor() {
        DubheMemoryRetrievalPolicy policy = DubheMemoryRetrievalPolicy.DEFAULT;
        for (double topScore : new double[]{DubheMemoryRetrievalPolicy.HOT_FLOOR, 0.30D, 0.55D, 0.90D}) {
            assertTrue(policy.hotScoreCutoff(topScore) <= topScore, "最相关的候选必须能进 Hot: top=" + topScore);
        }
    }

    @Test
    void cutoffsStayOrderedHotThenWarmThenCold() {
        DubheMemoryRetrievalPolicy policy = DubheMemoryRetrievalPolicy.DEFAULT;
        for (double topScore : new double[]{0.0D, 0.10D, 0.35D, 1.0D}) {
            assertTrue(policy.hotScoreCutoff(topScore) >= policy.warmScoreCutoff(topScore),
                    "Hot 线应不小于 Warm 线: top=" + topScore);
            assertTrue(policy.warmScoreCutoff(topScore) > policy.coldScoreCutoff(),
                    "Warm 线应大于 Cold 线: top=" + topScore);
        }
    }

    @Test
    void scoreThresholdsAndBudgetRatiosAreClampedIndependently() {
        DubheMemoryRetrievalPolicy policy = new DubheMemoryRetrievalPolicy(
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
        DubheMemoryRetrievalPolicy policy = DubheMemoryRetrievalPolicy.DEFAULT.forTier(DubheContextTier.COMPACT);

        assertEquals(DubheMemoryRetrievalPolicy.CHAIN_RADIUS_COMPACT, policy.chainExpansionRadius());
        assertEquals(DubheMemoryRetrievalPolicy.CHAIN_BREADTH_COMPACT, policy.chainExpansionBreadth(), "紧凑档应为前一条加后一条");
    }

    @Test
    void widerTiersWidenChainExpansionAndLeaveOtherKnobsAlone() {
        DubheMemoryRetrievalPolicy standard = DubheMemoryRetrievalPolicy.DEFAULT.forTier(DubheContextTier.STANDARD);
        DubheMemoryRetrievalPolicy extended = DubheMemoryRetrievalPolicy.DEFAULT.forTier(DubheContextTier.EXTENDED);
        DubheMemoryRetrievalPolicy large = DubheMemoryRetrievalPolicy.DEFAULT.forTier(DubheContextTier.LARGE);

        assertTrue(standard.chainExpansionRadius() > DubheMemoryRetrievalPolicy.CHAIN_RADIUS_COMPACT);
        assertTrue(extended.chainExpansionRadius() > standard.chainExpansionRadius());
        assertTrue(large.chainExpansionRadius() >= extended.chainExpansionRadius());
        assertEquals(standard.hotScoreCutoff(1.0D), DubheMemoryRetrievalPolicy.DEFAULT.hotScoreCutoff(1.0D), "档位只改链式发散");
        assertEquals(standard.hotBudgetRatio(), DubheMemoryRetrievalPolicy.DEFAULT.hotBudgetRatio(), "档位只改链式发散");
    }

    @Test
    void unknownTierBehavesLikeTheCompactTier() {
        DubheMemoryRetrievalPolicy unresolved = DubheMemoryRetrievalPolicy.DEFAULT.forTier(null);

        assertEquals(DubheContextTier.COMPACT, DubheContextTier.of(0), "未知预算必须落到最保守档");
        assertEquals(DubheMemoryRetrievalPolicy.CHAIN_BREADTH_COMPACT, unresolved.chainExpansionBreadth());
    }
}
