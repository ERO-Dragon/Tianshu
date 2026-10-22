package com.rheinmetal.tianshu.function.auxilium.event;

/**
 * Outcome of deciding what to do with one event.
 *
 * @param action      matrix outcome for this event
 * @param tier        effective tier, after the built-in table and the {@code low} fallback
 * @param blacklisted effective blacklist flag, after the built-in table and {@code suppress}
 * @param speech      sentence to speak, {@code null} when the action does not speak
 * @param appendText  text to append right now, {@code null} when the entry is folded into an
 *                    aggregate window or the event is dropped
 * @param folded      {@code true} when the entry was folded into an aggregate window and will be
 *                    appended when that window closes
 */
public record AXEventDecision(AXEventAction action, AXEventTier tier, boolean blacklisted,
                              AXEventText speech, String appendText, boolean folded) {

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
}
