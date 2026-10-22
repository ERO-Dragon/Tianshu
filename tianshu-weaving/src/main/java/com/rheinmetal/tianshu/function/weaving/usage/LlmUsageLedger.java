package com.rheinmetal.tianshu.function.weaving.usage;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Accumulates LLM token usage per attribution key and keeps both a running total and a today
 * bucket.
 *
 * <p>Attribution comes from the request envelope's {@code sourceId}: every request carries the
 * identity of the module that issued it, so "how much did AX use" is measurable without inventing
 * a new instrumentation layer.
 *
 * <p>A cache split is recorded only when the provider actually reported one. An unreported split
 * stores no hit and no miss figure and increments no reported-request count, so the ledger can tell
 * "0 cached tokens" from "not measured" (see {@link LlmUsageCounter#hitRatio()}). Pricing does not
 * depend on that distinction: {@link LlmPricing} derives the uncached quantity from the input total,
 * which prices a silent provider as a full miss instead of understating the amount.
 *
 * <p>Writes happen at request frequency (a few per second at most), so a plain monitor is used
 * instead of a lock-free structure.
 */
public final class LlmUsageLedger {
    private final Map<String, LlmUsageCounter> byModule = new TreeMap<>();
    private LlmUsageCounter total = LlmUsageCounter.zero();
    private LlmUsageCounter today = LlmUsageCounter.zero();
    private long todayEpochDay = LocalDate.now().toEpochDay();

    /**
     * Records one finished request.
     *
     * @param sourceId           issuing module, taken from the envelope
     * @param inputTokens        total prompt tokens reported by the provider
     * @param cacheHit           prompt tokens the provider served from its cache
     * @param cacheMiss          prompt tokens it did not serve from cache
     * @param outputTokens       generated tokens
     * @param cacheSplitReported whether the provider reported a split at all; when false both cache
     *                           figures are ignored rather than counted as a measured zero
     */
    public synchronized void record(
            String sourceId,
            long inputTokens,
            long cacheHit,
            long cacheMiss,
            long outputTokens,
            boolean cacheSplitReported
    ) {
        if (inputTokens <= 0L && outputTokens <= 0L) {
            return;
        }
        rolloverIfNeeded();
        String key = sourceId == null || sourceId.isBlank() ? "unknown" : sourceId;
        long reportedHit = cacheSplitReported ? Math.max(0L, cacheHit) : 0L;
        long reportedMiss = cacheSplitReported ? Math.max(0L, cacheMiss) : 0L;
        long reportedRequests = cacheSplitReported ? 1L : 0L;
        byModule.merge(
                key,
                LlmUsageCounter.zero()
                        .plus(1L, inputTokens, reportedHit, reportedMiss, outputTokens, reportedRequests),
                LlmUsageCounter::combine
        );
        total = total.plus(1L, inputTokens, reportedHit, reportedMiss, outputTokens, reportedRequests);
        today = today.plus(1L, inputTokens, reportedHit, reportedMiss, outputTokens, reportedRequests);
    }

    public synchronized LlmUsageSnapshot snapshot() {
        rolloverIfNeeded();
        return new LlmUsageSnapshot(new HashMap<>(byModule), total, today, todayEpochDay);
    }

    /** Clears the today bucket when the date changes; the running total is unaffected. */
    private void rolloverIfNeeded() {
        long epochDay = LocalDate.now(ZoneId.systemDefault()).toEpochDay();
        if (epochDay != todayEpochDay) {
            todayEpochDay = epochDay;
            today = LlmUsageCounter.zero();
        }
    }
}
