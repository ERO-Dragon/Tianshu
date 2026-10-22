package com.rheinmetal.tianshu.function.auxilium.event;

/**
 * Outcome of deciding what to do with one event.
 *
 * <p>The decision is deliberately wording free: it only says which matrix cell applies and whether the
 * entry is appended now or folded into a window. The sentence itself belongs to the producer and stays
 * on the {@link AXEventDescriptor}, so there is exactly one place a line can come from.
 *
 * @param action      matrix outcome for this event
 * @param tier        effective tier, after the built-in table and the {@code low} fallback
 * @param blacklisted effective blacklist flag, after the built-in table and {@code suppress}
 * @param folded      {@code true} when the entry was folded into an aggregate window and will be
 *                    appended when that window closes
 */
public record AXEventDecision(AXEventAction action, AXEventTier tier, boolean blacklisted, boolean folded) {

    public AXEventDecision {
        if (action == null) {
            throw new IllegalArgumentException("event action is required");
        }
        if (tier == null) {
            throw new IllegalArgumentException("event tier is required");
        }
    }

    public boolean appends() {
        return action.appends();
    }

    /** True when the caller should speak this sentence without waiting for an idle window. */
    public boolean speaksImmediately() {
        return action == AXEventAction.APPEND_AND_SPEAK;
    }

    /** True when the caller should queue the sentence until the player is idle. */
    public boolean buffersSpeech() {
        return action == AXEventAction.APPEND_AND_BUFFER;
    }
}
