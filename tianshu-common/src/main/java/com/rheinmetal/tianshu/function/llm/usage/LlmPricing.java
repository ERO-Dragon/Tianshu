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
 * <p>One currency unit is whatever the configured prices are denominated in — the same unit for all
 * three prices, never mixed. The configured defaults are DeepSeek's mainland-China list price, so
 * the unit is CNY; the display layer decides how to render the amount.
 *
 * <p>Money is held as an integer count of "micro units" (1e-6 of one currency unit) so that
 * accumulating many requests never drifts. The {@code double} accepted by {@link #microsFromPrice}
 * is quantised exactly once, there.
 *
 * <p><strong>These prices never produce an authoritative amount.</strong> The provider answers
 * balance and cost questions itself (see {@code function.llm.account}), and those answers are what
 * the HUD shows as accurate. Anything derived from this record is a local estimate kept next to
 * them for reconciliation only: it must be labelled as an estimate wherever it is displayed, must
 * not be added to, compared for equality with, or used to correct a provider figure, and must never
 * be described as a bill.
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

    /**
     * Cost of one request in micro units, with cached and uncached input priced separately.
     *
     * <p>The uncached quantity is always derived as {@code inputTokens - cacheHitInputTokens} and is
     * never a separate figure the caller supplies. A provider that reports no cache split looks
     * exactly like a fully uncached request, which is the only safe reading: inventing hits would
     * understate the amount, while the worst case of this rule is the full-miss upper bound. Cache
     * hits are clamped to the input total so that no input token is ever billed twice.
     *
     * @param inputTokens         total prompt tokens the provider reported
     * @param cacheHitInputTokens prompt tokens it served from its prefix cache; 0 when unreported
     * @param outputTokens        generated tokens
     */
    public long costMicros(long inputTokens, long cacheHitInputTokens, long outputTokens) {
        long input = Math.max(0L, inputTokens);
        long hit = Math.min(input, Math.max(0L, cacheHitInputTokens));
        long miss = input - hit;
        long missMicros = miss * inputMissMicrosPerMillion / TOKENS_PER_MILLION;
        long hitMicros = hit * inputHitMicrosPerMillion / TOKENS_PER_MILLION;
        long outputMicros = Math.max(0L, outputTokens) * outputMicrosPerMillion / TOKENS_PER_MILLION;
        return missMicros + hitMicros + outputMicros;
    }
}
