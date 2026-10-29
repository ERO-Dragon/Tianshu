package com.rheinmetal.tianshu.client.presence.hud;

/**
 * The visible/hidden state machine every HUD element shares.
 *
 * <p>One element being hidden, shown or re-entered is the same decision for every element type, so it lives in
 * one place: an element only decides what its content is once this state says it is on screen.</p>
 */
final class PresenceHudElementStateTracker {
    private PresenceHudElementState state = PresenceHudElementState.HIDDEN;
    private long enteredAtMillis;

    PresenceHudElementState state() {
        return state;
    }

    long enteredAtMillis() {
        return enteredAtMillis;
    }

    void enter(PresenceHudElementState nextState, long nowMillis) {
        PresenceHudElementState next = nextState == null ? PresenceHudElementState.HIDDEN : nextState;
        if (state != next) {
            state = next;
            enteredAtMillis = nowMillis;
        } else if (enteredAtMillis <= 0L) {
            // The first visible frame is the moment the element appeared, not millisecond zero.
            enteredAtMillis = nowMillis;
        }
    }

    void reset() {
        state = PresenceHudElementState.HIDDEN;
        enteredAtMillis = 0L;
    }
}
