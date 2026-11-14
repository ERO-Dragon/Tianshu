package com.rheinmetal.tianshu.function.presence.hud;

/**
 * Timing values supplied by the layer; this type never reads a system clock.
 *
 * <p>Two clocks, both filled by the caller: {@code updatedAtMillis} is monotonic render time (animation smoothness),
 * {@code wallAtMillis} is the wall clock (lease deadlines). A renderer that compares a deadline against render time
 * drifts by however long the process has been up, so the two are never interchangeable.</p>
 */
public record PresenceHudElementTiming(
        long updatedAtMillis,
        long stateEnteredAtMillis,
        long wallAtMillis
) {
    public PresenceHudElementTiming(long updatedAtMillis, long stateEnteredAtMillis) {
        this(updatedAtMillis, stateEnteredAtMillis, updatedAtMillis);
    }

    public PresenceHudElementTiming {
        updatedAtMillis = Math.max(0L, updatedAtMillis);
        if (stateEnteredAtMillis <= 0L) {
            stateEnteredAtMillis = updatedAtMillis;
        } else {
            stateEnteredAtMillis = Math.max(0L, stateEnteredAtMillis);
        }
        wallAtMillis = wallAtMillis <= 0L ? System.currentTimeMillis() : wallAtMillis;
    }
}
