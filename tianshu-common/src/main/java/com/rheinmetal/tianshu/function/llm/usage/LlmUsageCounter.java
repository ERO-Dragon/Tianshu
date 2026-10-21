package com.rheinmetal.tianshu.function.llm.usage;

/**
 * Accumulated counters for one attribution key: total input, cached input, uncached input, output
 * and the number of requests.
 *
 * <p>Immutable, so snapshots can be read without locking.
 */
public record LlmUsageCounter(
        long requests,
        long inputTokens,
        long cacheHitInputTokens,
        long cacheMissInputTokens,
        long outputTokens
) {
    public static LlmUsageCounter zero() {
        return new LlmUsageCounter(0L, 0L, 0L, 0L, 0L);
    }

    public LlmUsageCounter plus(long newRequests, long newInput, long newHit, long newMiss, long newOutput) {
        return new LlmUsageCounter(
                requests + Math.max(0L, newRequests),
                inputTokens + Math.max(0L, newInput),
                cacheHitInputTokens + Math.max(0L, newHit),
                cacheMissInputTokens + Math.max(0L, newMiss),
                outputTokens + Math.max(0L, newOutput)
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
                outputTokens + other.outputTokens
        );
    }

    /** Input cache-hit ratio; returns -1 when there was no input, meaning "no data", not "0%". */
    public double hitRatio() {
        long inputTotal = cacheHitInputTokens + cacheMissInputTokens;
        if (inputTotal <= 0L) {
            return -1D;
        }
        return (double) cacheHitInputTokens / (double) inputTotal;
    }
}
