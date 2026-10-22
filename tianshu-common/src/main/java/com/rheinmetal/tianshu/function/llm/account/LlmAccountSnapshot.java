package com.rheinmetal.tianshu.function.llm.account;

/**
 * One provider answer about an account, kept per {@link LlmAccountKey}.
 *
 * <p>Money stays in integer micro units (1e-6 of one currency unit), matching
 * {@link com.rheinmetal.tianshu.function.llm.usage.LlmPricing}, so accumulating never drifts and no
 * rounding rule has to be invented at the display layer.
 *
 * <p>{@link #UNKNOWN_AMOUNT} means "the provider did not give this figure" and is deliberately not
 * zero: a provider that reports only a balance must not look like it reported a cost of 0. The
 * display layer decides per amount, so partially available answers stay usable.
 *
 * <p>This snapshot is the authoritative side of the HUD's money rows. Anything derived from
 * {@link com.rheinmetal.tianshu.function.llm.usage.LlmPricing} is a local estimate and is never
 * merged into these fields.
 */
public record LlmAccountSnapshot(
        LlmAccountKey key,
        LlmAccountQueryState state,
        long todayCostMicros,
        long totalCostMicros,
        long balanceMicros,
        String currencyCode,
        long fetchedAtMillis,
        String failureKey
) {
    /** Sentinel for "this amount was not reported"; every amount below zero is treated as unknown. */
    public static final long UNKNOWN_AMOUNT = -1L;

    public LlmAccountSnapshot {
        key = key == null ? LlmAccountKey.unspecified() : key;
        state = state == null ? LlmAccountQueryState.UNWIRED : state;
        currencyCode = currencyCode == null ? "" : currencyCode.trim();
        fetchedAtMillis = Math.max(0L, fetchedAtMillis);
        failureKey = failureKey == null ? "" : failureKey.trim();
    }

    /** No probe is bound: the HUD shows a placeholder, the amounts stay unknown. */
    public static LlmAccountSnapshot unwired(LlmAccountKey key) {
        return new LlmAccountSnapshot(
                key,
                LlmAccountQueryState.UNWIRED,
                UNKNOWN_AMOUNT,
                UNKNOWN_AMOUNT,
                UNKNOWN_AMOUNT,
                "",
                0L,
                ""
        );
    }

    /** A request was dispatched; amounts are withheld until it answers. */
    public static LlmAccountSnapshot querying(LlmAccountKey key) {
        return new LlmAccountSnapshot(
                key,
                LlmAccountQueryState.QUERYING,
                UNKNOWN_AMOUNT,
                UNKNOWN_AMOUNT,
                UNKNOWN_AMOUNT,
                "",
                0L,
                ""
        );
    }

    /**
     * A request answered. Any figure the provider did not include must be passed as
     * {@link #UNKNOWN_AMOUNT} rather than as zero.
     */
    public static LlmAccountSnapshot available(
            LlmAccountKey key,
            long todayCostMicros,
            long totalCostMicros,
            long balanceMicros,
            String currencyCode,
            long fetchedAtMillis
    ) {
        return new LlmAccountSnapshot(
                key,
                LlmAccountQueryState.AVAILABLE,
                todayCostMicros,
                totalCostMicros,
                balanceMicros,
                currencyCode,
                fetchedAtMillis,
                ""
        );
    }

    /** A request ended without a usable answer; {@code failureKey} is a message key when known. */
    public static LlmAccountSnapshot failed(LlmAccountKey key, String failureKey, long failedAtMillis) {
        return new LlmAccountSnapshot(
                key,
                LlmAccountQueryState.FAILED,
                UNKNOWN_AMOUNT,
                UNKNOWN_AMOUNT,
                UNKNOWN_AMOUNT,
                "",
                failedAtMillis,
                failureKey
        );
    }

    /** Whether the given figure is a provider-reported amount and may be displayed as accurate. */
    public static boolean amountKnown(long micros) {
        return micros >= 0L;
    }
}
