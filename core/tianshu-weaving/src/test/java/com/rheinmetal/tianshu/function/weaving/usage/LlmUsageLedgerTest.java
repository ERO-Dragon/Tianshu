package com.rheinmetal.tianshu.function.weaving.usage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmUsageLedgerTest {
    @Test
    void accumulatesPerModuleAndInTotal() {
        LlmUsageLedger ledger = new LlmUsageLedger();

        ledger.record("tianshu:ax", 1_000L, 800L, 200L, 100L, true);
        ledger.record("tianshu:ax", 500L, 400L, 100L, 50L, true);
        ledger.record("tianshu:other", 200L, 0L, 200L, 20L, true);

        LlmUsageSnapshot snapshot = ledger.snapshot();

        assertEquals(1_700L, snapshot.total().inputTokens());
        assertEquals(1_200L, snapshot.total().cacheHitInputTokens());
        assertEquals(500L, snapshot.total().cacheMissInputTokens());
        assertEquals(170L, snapshot.total().outputTokens());
        assertEquals(3L, snapshot.total().requests());
        assertEquals(3L, snapshot.total().cacheReportedRequests());

        assertEquals(1_500L, snapshot.module("tianshu:ax").inputTokens());
        assertEquals(2L, snapshot.module("tianshu:ax").requests());
        assertEquals(200L, snapshot.module("tianshu:other").inputTokens());
        assertEquals(LlmUsageCounter.zero(), snapshot.module("never-called"));
    }

    @Test
    void hitRatioIsUndefinedWithoutInputInsteadOfZero() {
        LlmUsageLedger ledger = new LlmUsageLedger();

        ledger.record("tianshu:ax", 0L, 0L, 0L, 40L, true);

        LlmUsageSnapshot snapshot = ledger.snapshot();
        assertEquals(-1D, snapshot.total().hitRatio(), "output-only usage has no hit ratio to report");
    }

    @Test
    void hitRatioReflectsCacheHits() {
        LlmUsageLedger ledger = new LlmUsageLedger();

        ledger.record("tianshu:ax", 1_000L, 900L, 100L, 10L, true);

        assertEquals(0.9D, ledger.snapshot().total().hitRatio(), 1e-9);
    }

    @Test
    void reportedZeroHitsIsAMeasurementWhileAnUnreportedSplitIsNot() {
        LlmUsageLedger reported = new LlmUsageLedger();
        reported.record("tianshu:ax", 1_000L, 0L, 1_000L, 10L, true);

        // The provider measured nothing cached: that is a real 0%, and it must show as such.
        assertEquals(1L, reported.snapshot().total().cacheReportedRequests());
        assertEquals(0D, reported.snapshot().total().hitRatio(), 1e-9);

        LlmUsageLedger silent = new LlmUsageLedger();
        silent.record("tianshu:ax", 1_000L, 0L, 0L, 10L, false);

        // The provider said nothing: no ratio exists, so the display has to say "no data".
        assertFalse(silent.snapshot().total().cacheReported());
        assertEquals(-1D, silent.snapshot().total().hitRatio());
        assertEquals(0L, silent.snapshot().total().cacheMissInputTokens(),
                "an unreported split must not be recorded as a measured 0% hit rate");
        assertEquals(1_000L, silent.snapshot().total().inputTokens());
    }

    @Test
    void unreportedRequestsStayOutOfTheRatioButKeepTheTokenTotals() {
        LlmUsageLedger ledger = new LlmUsageLedger();
        ledger.record("tianshu:ax", 1_000L, 500L, 500L, 10L, true);
        ledger.record("tianshu:ax", 9_000L, 0L, 0L, 90L, false);

        LlmUsageSnapshot snapshot = ledger.snapshot();

        assertEquals(2L, snapshot.total().requests());
        assertEquals(1L, snapshot.total().cacheReportedRequests());
        assertEquals(10_000L, snapshot.total().inputTokens());
        // 500 / (500 + 500): only the reporting request counts, so silence cannot drag the ratio to 5%.
        assertEquals(0.5D, snapshot.total().hitRatio(), 1e-9);
    }

    @Test
    void costUsesSeparateHitAndMissPrices() {
        // A cache hit costs 1/50 of a miss: 0.02 against 1.0 per million tokens, output 2.0.
        LlmPricing pricing = new LlmPricing(
                LlmPricing.microsFromPrice(1.0D),
                LlmPricing.microsFromPrice(0.02D),
                LlmPricing.microsFromPrice(2.0D)
        );
        LlmUsageLedger ledger = new LlmUsageLedger();

        ledger.record("tianshu:ax", 1_000_000L, 1_000_000L, 0L, 0L, true);
        long allHit = ledger.snapshot().totalCostMicros(pricing);

        LlmUsageLedger missLedger = new LlmUsageLedger();
        missLedger.record("tianshu:ax", 1_000_000L, 0L, 1_000_000L, 0L, true);
        long allMiss = missLedger.snapshot().totalCostMicros(pricing);

        // Micro units: 0.02 currency per million tokens = 20_000 micros per million tokens.
        assertEquals(20_000L, allHit, "all hits: one million tokens at 0.02");
        assertEquals(1_000_000L, allMiss, "all misses: one million tokens at 1.0");
        assertTrue(allHit < allMiss);
    }

    @Test
    void unreportedCacheSplitIsPricedAsAFullMiss() {
        LlmPricing pricing = new LlmPricing(
                LlmPricing.microsFromPrice(1.0D),
                LlmPricing.microsFromPrice(0.02D),
                LlmPricing.microsFromPrice(2.0D)
        );
        LlmUsageLedger reported = new LlmUsageLedger();
        reported.record("tianshu:ax", 1_000_000L, 0L, 1_000_000L, 0L, true);
        LlmUsageLedger silent = new LlmUsageLedger();
        silent.record("tianshu:ax", 1_000_000L, 0L, 0L, 0L, false);

        // Silence must not understate the money: the miss side is derived as input - hit, so both
        // recordings produce the same amount.
        assertEquals(reported.snapshot().totalCostMicros(pricing), silent.snapshot().totalCostMicros(pricing));
        assertEquals(1_000_000L, silent.snapshot().totalCostMicros(pricing));
    }

    @Test
    void unconfiguredPricingReportsNoCostInsteadOfZero() {
        LlmUsageLedger ledger = new LlmUsageLedger();
        ledger.record("tianshu:ax", 1_000L, 500L, 500L, 100L, true);

        assertEquals(-1L, ledger.snapshot().totalCostMicros(LlmPricing.free()));
        assertEquals(-1L, ledger.snapshot().todayCostMicros(null));
    }
}
