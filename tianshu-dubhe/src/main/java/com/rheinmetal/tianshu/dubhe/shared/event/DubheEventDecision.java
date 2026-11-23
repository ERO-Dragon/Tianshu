package com.rheinmetal.tianshu.dubhe.shared.event;

/**
 * Outcome of deciding what to do with one event.
 *
 * <p>The decision is deliberately wording free: it names the handling class, the frequency row and the state that
 * produced it, and whether the occurrence was retained in a fold window instead of being appended now.</p>
 *
 * @param eventClass how this occurrence is handled, read off the matrix for the corrected row and the state
 * @param tier       effective row, after the built-in table and the frequency correction
 * @param state      effective state, after the built-in table and the per-occurrence flags
 * @param folded     {@code true} when the occurrence was retained in a fold window and will be appended when that
 *                   window closes
 */
public record DubheEventDecision(DubheEventClass eventClass, DubheEventTier tier, DubheEventState state,
                                 boolean folded) {

    public DubheEventDecision {
        if (eventClass == null) {
            throw new IllegalArgumentException("event class is required");
        }
        if (tier == null) {
            throw new IllegalArgumentException("event tier is required");
        }
        if (state == null) {
            throw new IllegalArgumentException("event state is required");
        }
    }

    public boolean appends() {
        return eventClass.appends();
    }

    /** True when the line is window-only and must be excluded from compaction and retrieval. */
    public boolean transientContext() {
        return eventClass.transientContext();
    }

    /** True when the caller should speak this sentence without waiting for a lull. */
    public boolean speaksImmediately() {
        return eventClass == DubheEventClass.IMMEDIATE;
    }

    /** True when the caller should hold the sentence for the next voluntary turn. */
    public boolean buffersSpeech() {
        return eventClass == DubheEventClass.DEFERRED;
    }
}