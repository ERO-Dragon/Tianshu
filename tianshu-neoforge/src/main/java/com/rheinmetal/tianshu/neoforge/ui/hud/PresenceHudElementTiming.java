package com.rheinmetal.tianshu.neoforge.ui.hud;

public record PresenceHudElementTiming(
        long updatedAtMillis,
        long stateEnteredAtMillis
) {
    public PresenceHudElementTiming {
        long now = System.currentTimeMillis();
        if (updatedAtMillis <= 0L) {
            updatedAtMillis = now;
        }
        if (stateEnteredAtMillis <= 0L) {
            stateEnteredAtMillis = updatedAtMillis;
        }
    }

    public long stateAgeMillis() {
        return Math.max(0L, updatedAtMillis - stateEnteredAtMillis);
    }
}
