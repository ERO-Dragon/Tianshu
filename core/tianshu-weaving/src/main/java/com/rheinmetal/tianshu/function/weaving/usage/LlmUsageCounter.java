package com.rheinmetal.tianshu.function.weaving.usage;

/**
 * Accumulated counters for one attribution key: total input, cached input, uncached input, output,
 * the number of requests, and how many of those requests reported a cache split.
 *
 * <p>Immutable, so snapshots can be read without locking.
 *
 * <p>The reported-request count exists because "the provider measured 0 cached tokens" and "the
 * provider reported nothing" are different facts. Without it, a silent provider would be rendered as
 * a 0% hit rate — a number that looks measured and is not. Only requests that reported a split enter
 * {@link #hitRatio()}. Money is a separate question and is answered conservatively by
 * {@link LlmPricing}: an unreported prompt is priced as fully uncached.
 */
public record LlmUsageCounter(
        long requests,
        long inputTokens,
        long cacheHitInputTokens,
        long cacheMissInputTokens,
        long outputTokens,
        long cacheReportedRequests
) {
    public static LlmUsageCounter zero() {
        return new LlmUsageCounter(0L, 0L, 0L, 0L, 0L, 0L);
    }

    public LlmUsageCounter plus(
            long newRequests,
            long newInput,
            long newHit,
            long newMiss,
            long newOutput,
            long newCacheReportedRequests
    ) {
        return new LlmUsageCounter(
                requests + Math.max(0L, newRequests),
                inputTokens + Math.max(0L, newInput),
                cacheHitInputTokens + Math.max(0L, newHit),
                cacheMissInputTokens + Math.max(0L, newMiss),
                outputTokens + Math.max(0L, newOutput),
                cacheReportedRequests + Math.max(0L, newCacheReportedRequests)
        );
    }

    /** Merges another counter into this one; used for per-key accumulation. */
    public LlmUsageCounter combine(LlmUsageCounter other) {
        if (other == null) {
            return this;
        }
        return new LlmUsageCounter(
                requests + other.requests,
                inputTokens + other.inputTokens,
                cacheHitInputTokens + other.cacheHitInputTokens,
                cacheMissInputTokens + other.cacheMissInputTokens,
                outputTokens + other.outputTokens,
                cacheReportedRequests + other.cacheReportedRequests
        );
    }

    /** Whether any request reported a cache split at all; when false a ratio is not a measurement. */
    public boolean cacheReported() {
        return cacheReportedRequests > 0L;
    }

    /**
     * Input cache-hit ratio over the requests that reported a cache split.
     *
     * <p>Requests that reported nothing are left out of both the numerator and the denominator: they
     * carry no hit/miss measurement, so counting them would turn "unknown" into "0%".
     *
     * @return -1 when no request reported a split, meaning "no data", not "0%"
     */
    public double hitRatio() {
        long reportedInputTokens = cacheHitInputTokens + cacheMissInputTokens;
        if (cacheReportedRequests <= 0L || reportedInputTokens <= 0L) {
            return -1D;
        }
        return (double) cacheHitInputTokens / (double) reportedInputTokens;
    }
}
