package com.rheinmetal.tianshu.function.llm.usage;

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
     * @param sourceId     issuing module, taken from the envelope
     * @param inputTokens  total prompt tokens reported by the provider
     * @param cacheHit     prompt tokens the provider served from its cache
     * @param cacheMiss    prompt tokens it did not serve from cache
     * @param outputTokens generated tokens
     */
    public synchronized void record(
            String sourceId,
            long inputTokens,
            long cacheHit,
            long cacheMiss,
            long outputTokens
    ) {
        if (inputTokens <= 0L && outputTokens <= 0L) {
            return;
        }
        rolloverIfNeeded();
        String key = sourceId == null || sourceId.isBlank() ? "unknown" : sourceId;
        byModule.merge(
                key,
                LlmUsageCounter.zero().plus(1L, inputTokens, cacheHit, cacheMiss, outputTokens),
                LlmUsageCounter::combine
        );
        total = total.plus(1L, inputTokens, cacheHit, cacheMiss, outputTokens);
        today = today.plus(1L, inputTokens, cacheHit, cacheMiss, outputTokens);
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
