package com.rheinmetal.tianshu.neoforge.ui.hud;

/** Timing values supplied by the renderer; this type never reads a system clock. */
public record PresenceHudElementTiming(
        long updatedAtMillis,
        long stateEnteredAtMillis
) {
    public PresenceHudElementTiming {
        updatedAtMillis = Math.max(0L, updatedAtMillis);
        if (stateEnteredAtMillis <= 0L) {
            stateEnteredAtMillis = updatedAtMillis;
        } else {
            stateEnteredAtMillis = Math.max(0L, stateEnteredAtMillis);
        }
    }

}
