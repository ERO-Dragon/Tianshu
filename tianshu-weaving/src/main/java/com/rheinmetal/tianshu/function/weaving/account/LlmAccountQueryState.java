package com.rheinmetal.tianshu.function.weaving.account;

/**
 * Lifecycle of one account query.
 *
 * <p>An amount the provider has not answered yet must not be rendered as zero, so "no answer" has
 * dedicated states instead of a default numeric value: {@link #UNWIRED} while no probe is bound,
 * {@link #QUERYING} while a request is in flight, {@link #FAILED} once a request ended without a
 * usable answer. Only {@link #AVAILABLE} carries amounts worth showing.
 */
public enum LlmAccountQueryState {
    /** No probe is wired up yet: the HUD shows a placeholder instead of a number. */
    UNWIRED,
    /** A request is in flight; the previous answer is not reused. */
    QUERYING,
    /** The provider answered; the amounts in the snapshot are authoritative for that instant. */
    AVAILABLE,
    /** The request failed; the snapshot carries the failure timestamp, never a fallback estimate. */
    FAILED;
}
