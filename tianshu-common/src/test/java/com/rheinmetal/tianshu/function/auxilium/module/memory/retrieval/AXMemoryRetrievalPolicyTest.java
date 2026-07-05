package com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval;

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
    void thresholdsAreOrderedHotGtWarmGtCold() {
        AXMemoryRetrievalPolicy policy = AXMemoryRetrievalPolicy.DEFAULT;
        assertTrue(policy.hotScoreThreshold() > policy.warmScoreThreshold(), "Hot 阈值应大于 Warm 阈值");
        assertTrue(policy.warmScoreThreshold() > policy.coldScoreThreshold(), "Warm 阈值应大于 Cold 阈值");
    }

    @Test
    void warmRatioIsClampedToRemainingAfterHot() {
        AXMemoryRetrievalPolicy policy = new AXMemoryRetrievalPolicy(
                0.56D, 0.92D, 256, 48, 4,
                0.10D, 3650.0D,
                0.82D, 0.35D, 1,
                0.60D, 0.35D, 0.15D,
                0.90D, 0.50D
        );
        assertTrue(policy.hotBudgetRatio() + policy.warmBudgetRatio() <= 1.0D, "Hot + Warm 比例之和应不超 1");
        assertTrue(policy.coldBudgetRatio() >= 0.0D, "Cold 比例应为非负");
    }
}
