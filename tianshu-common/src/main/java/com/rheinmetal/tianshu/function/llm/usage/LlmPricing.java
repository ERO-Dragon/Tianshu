package com.rheinmetal.tianshu.function.llm.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Per-million-token prices used to turn token usage into money.
 *
 * <p>The three prices must stay separate: providers charge cached input one to two orders of
 * magnitude less than uncached input, so charging both at the same rate would overstate the cost
 * by several times.
 *
 * <p>Money is held as an integer count of "micro units" (1e-6 of one currency unit) so that
 * accumulating many requests never drifts. The {@code double} accepted by the constructor is
 * quantised exactly once, here.
 */
public record LlmPricing(
        long inputMissMicrosPerMillion,
        long inputHitMicrosPerMillion,
        long outputMicrosPerMillion
) {
    private static final long TOKENS_PER_MILLION = 1_000_000L;

    public LlmPricing {
        inputMissMicrosPerMillion = Math.max(0L, inputMissMicrosPerMillion);
        inputHitMicrosPerMillion = Math.max(0L, inputHitMicrosPerMillion);
        outputMicrosPerMillion = Math.max(0L, outputMicrosPerMillion);
    }

    /** All prices zero: no price has been configured, so no amount may be shown at all. */
    public static LlmPricing free() {
        return new LlmPricing(0L, 0L, 0L);
    }

    /** Whether any price is configured; when false the UI must hide amounts instead of showing 0. */
    public boolean configured() {
        return inputMissMicrosPerMillion > 0L || inputHitMicrosPerMillion > 0L || outputMicrosPerMillion > 0L;
    }

    /**
     * @param pricePerMillion price for one million tokens, in the currency the player sees
     * @return the same price expressed in micro units
     */
    public static long microsFromPrice(double pricePerMillion) {
        if (!(pricePerMillion > 0D)) {
            return 0L;
        }
        return BigDecimal.valueOf(pricePerMillion)
                .multiply(BigDecimal.valueOf(1_000_000L))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** Cost of one request in micro units, with cached and uncached input priced separately. */
    public long costMicros(long cacheMissInputTokens, long cacheHitInputTokens, long outputTokens) {
        long miss = Math.max(0L, cacheMissInputTokens) * inputMissMicrosPerMillion / TOKENS_PER_MILLION;
        long hit = Math.max(0L, cacheHitInputTokens) * inputHitMicrosPerMillion / TOKENS_PER_MILLION;
        long out = Math.max(0L, outputTokens) * outputMicrosPerMillion / TOKENS_PER_MILLION;
        return miss + hit + out;
    }
}
