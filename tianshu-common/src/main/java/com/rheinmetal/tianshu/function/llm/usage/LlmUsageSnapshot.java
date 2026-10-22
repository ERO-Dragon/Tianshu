package com.rheinmetal.tianshu.function.llm.usage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only view of the usage ledger, consumed by the HUD.
 *
 * <p>Per-module data stops at tokens and never reaches money: the provider only reports how much of
 * <em>this</em> request it served from cache, so cache hits cannot be attributed back to the module
 * whose content produced the shared prefix. Amounts are therefore computed globally only.
 */
public record LlmUsageSnapshot(
        Map<String, LlmUsageCounter> byModule,
        LlmUsageCounter total,
        LlmUsageCounter today,
        long todayEpochDay
) {
    public LlmUsageSnapshot {
        Map<String, LlmUsageCounter> copy = new LinkedHashMap<>(byModule == null ? Map.of() : byModule);
        byModule = Collections.unmodifiableMap(copy);
        total = total == null ? LlmUsageCounter.zero() : total;
        today = today == null ? LlmUsageCounter.zero() : today;
    }

    public static LlmUsageSnapshot empty() {
        return new LlmUsageSnapshot(Map.of(), LlmUsageCounter.zero(), LlmUsageCounter.zero(), 0L);
    }

    public LlmUsageCounter module(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) {
            return LlmUsageCounter.zero();
        }
        return byModule.getOrDefault(sourceId, LlmUsageCounter.zero());
    }

    /**
     * Cost of today in micro units; returns -1 when no price is configured.
     *
     * <p>This is a <strong>local estimate</strong> computed from {@link LlmPricing}, not a bill: the
     * authoritative amount is whatever the provider reports. The display layer keeps the two apart
     * and labels this one as an estimate.
     */
    public long todayCostMicros(LlmPricing pricing) {
        return costMicros(pricing, today);
    }

    /**
     * Cost of all recorded usage in micro units; returns -1 when no price is configured.
     *
     * <p>Local estimate for reconciliation only; see {@link #todayCostMicros(LlmPricing)}.
     */
    public long totalCostMicros(LlmPricing pricing) {
        return costMicros(pricing, total);
    }

    private static long costMicros(LlmPricing pricing, LlmUsageCounter counter) {
        if (pricing == null || !pricing.configured() || counter == null) {
            return -1L;
        }
        // The miss side is derived from the input total inside the pricing, so a request whose cache
        // split was never reported is priced as fully uncached instead of dropping out of the amount.
        return pricing.costMicros(counter.inputTokens(), counter.cacheHitInputTokens(), counter.outputTokens());
    }
}
