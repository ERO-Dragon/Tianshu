package com.rheinmetal.tianshu.function.llm.usage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmUsageLedgerTest {
    @Test
    void accumulatesPerModuleAndInTotal() {
        LlmUsageLedger ledger = new LlmUsageLedger();

        ledger.record("tianshu:ax", 1_000L, 800L, 200L, 100L);
        ledger.record("tianshu:ax", 500L, 400L, 100L, 50L);
        ledger.record("tianshu:other", 200L, 0L, 200L, 20L);

        LlmUsageSnapshot snapshot = ledger.snapshot();

        assertEquals(1_700L, snapshot.total().inputTokens());
        assertEquals(1_200L, snapshot.total().cacheHitInputTokens());
        assertEquals(500L, snapshot.total().cacheMissInputTokens());
        assertEquals(170L, snapshot.total().outputTokens());
        assertEquals(3L, snapshot.total().requests());

        assertEquals(1_500L, snapshot.module("tianshu:ax").inputTokens());
        assertEquals(2L, snapshot.module("tianshu:ax").requests());
        assertEquals(200L, snapshot.module("tianshu:other").inputTokens());
        assertEquals(LlmUsageCounter.zero(), snapshot.module("never-called"));
    }

    @Test
    void hitRatioIsUndefinedWithoutInputInsteadOfZero() {
        LlmUsageLedger ledger = new LlmUsageLedger();

        ledger.record("tianshu:ax", 0L, 0L, 0L, 40L);

        LlmUsageSnapshot snapshot = ledger.snapshot();
        assertEquals(-1D, snapshot.total().hitRatio(), "只有输出时命中率应表示「无数据」");
    }

    @Test
    void hitRatioReflectsCacheHits() {
        LlmUsageLedger ledger = new LlmUsageLedger();

        ledger.record("tianshu:ax", 1_000L, 900L, 100L, 10L);

        assertEquals(0.9D, ledger.snapshot().total().hitRatio(), 1e-9);
    }

    @Test
    void costUsesSeparateHitAndMissPrices() {
        // 命中价是未命中的 1/50：0.02 对 1.0（每百万 token），输出 2.0
        LlmPricing pricing = new LlmPricing(
                LlmPricing.microsFromPrice(1.0D),
                LlmPricing.microsFromPrice(0.02D),
                LlmPricing.microsFromPrice(2.0D)
        );
        LlmUsageLedger ledger = new LlmUsageLedger();

        ledger.record("tianshu:ax", 1_000_000L, 1_000_000L, 0L, 0L);
        long allHit = ledger.snapshot().totalCostMicros(pricing);

        LlmUsageLedger missLedger = new LlmUsageLedger();
        missLedger.record("tianshu:ax", 1_000_000L, 0L, 1_000_000L, 0L);
        long allMiss = missLedger.snapshot().totalCostMicros(pricing);

        // 微单位：0.02 货币/百万 token = 20_000 微单位/百万 token
        assertEquals(20_000L, allHit, "全部命中：百万 token × 0.02");
        assertEquals(1_000_000L, allMiss, "全部未命中：百万 token × 1.0");
        assertTrue(allHit < allMiss);
    }

    @Test
    void unconfiguredPricingReportsNoCostInsteadOfZero() {
        LlmUsageLedger ledger = new LlmUsageLedger();
        ledger.record("tianshu:ax", 1_000L, 500L, 500L, 100L);

        assertEquals(-1L, ledger.snapshot().totalCostMicros(LlmPricing.free()));
        assertEquals(-1L, ledger.snapshot().todayCostMicros(null));
    }
}
