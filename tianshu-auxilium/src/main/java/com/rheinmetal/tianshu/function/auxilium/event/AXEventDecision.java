package com.rheinmetal.tianshu.function.auxilium.event;

/**
 * Outcome of deciding what to do with one event.
 *
 * <p>The decision is deliberately wording free: it names the handling class, the tier and the blacklist flag that
 * produced it, and whether the entry was retained in a fold window instead of being appended now. The sentence
 * itself belongs to the producer and stays on the {@link AXEventDescriptor}, so there is exactly one place a line
 * can come from.
 *
 * @param eventClass  how this occurrence is handled, read off the policy table for the corrected tier and the list
 * @param tier        effective tier, after the built-in table and the frequency correction
 * @param blacklisted effective blacklist flag, after the built-in table and {@code suppress}
 * @param folded      {@code true} when the occurrence was retained in a fold window and will be appended when
 *                    that window closes
 */
public record AXEventDecision(AXEventClass eventClass, AXEventTier tier, boolean blacklisted, boolean folded) {

    public AXEventDecision {
        if (eventClass == null) {
            throw new IllegalArgumentException("event class is required");
        }
        if (tier == null) {
            throw new IllegalArgumentException("event tier is required");
        }
    }

    public boolean appends() {
        return eventClass.appends();
    }

    /** True when the caller should speak this sentence without waiting for a lull. */
    public boolean speaksImmediately() {
        return eventClass == AXEventClass.IMMEDIATE;
    }

    /** True when the caller should hold the sentence for the next voluntary turn. */
    public boolean buffersSpeech() {
        return eventClass == AXEventClass.DEFERRED;
    }
}
