package com.rheinmetal.tianshu.client.event;

/**
 * Crossing thresholds of the capture layer.
 *
 * <p>These are capture thresholds, not priorities: a crossing decides <em>whether</em> an event is
 * produced, while the tier table decides what happens to it. Every value has a configuration name so
 * a deployment can move it without touching code.
 *
 * <ul>
 *   <li>{@link #healthCriticalPoints()} - {@code ax.events.capture.healthCriticalPoints}, default 4</li>
 *   <li>{@link #hungerCriticalLevel()} - {@code ax.events.capture.hungerCriticalLevel}, default 6</li>
 * </ul>
 */
public interface AXGameEventThresholds {
    /** Health in half-heart points at or below which the player counts as critical: two hearts. */
    int DEFAULT_HEALTH_CRITICAL_POINTS = 4;
    /** Hunger bar value at or below which the player counts as critical: three shanks left. */
    int DEFAULT_HUNGER_CRITICAL_LEVEL = 6;

    AXGameEventThresholds DEFAULT = new AXGameEventThresholds() {
    };

    default int healthCriticalPoints() {
        return DEFAULT_HEALTH_CRITICAL_POINTS;
    }

    default int hungerCriticalLevel() {
        return DEFAULT_HUNGER_CRITICAL_LEVEL;
    }
}
