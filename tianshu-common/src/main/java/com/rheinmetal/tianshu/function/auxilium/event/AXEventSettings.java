package com.rheinmetal.tianshu.function.auxilium.event;

/**
 * Tunables of the event decision layer.
 *
 * <p>Every value here has a configuration name so a deployment can override it without touching
 * code; nothing in this layer may hardcode a gap or a threshold.
 *
 * <ul>
 *   <li>{@link #globalMinGapMillis()} - {@code ax.events.speech.globalMinGapSec}, default 60s</li>
 *   <li>{@link #sameKindMinGapMillis()} - {@code ax.events.speech.sameKindMinGapSec}, default 300s</li>
 *   <li>{@link #aggregateWindowMillis()} - {@code ax.events.aggregate.windowSec}, default 30s</li>
 *   <li>{@link #aggregateThreshold()} - {@code ax.events.aggregate.threshold}, default 3</li>
 * </ul>
 */
public interface AXEventSettings {
    long DEFAULT_GLOBAL_MIN_GAP_MILLIS = 60_000L;
    long DEFAULT_SAME_KIND_MIN_GAP_MILLIS = 300_000L;
    long DEFAULT_AGGREGATE_WINDOW_MILLIS = 30_000L;
    int DEFAULT_AGGREGATE_THRESHOLD = 3;

    AXEventSettings DEFAULT = new AXEventSettings() {
    };

    /** Minimum gap between any two immediate speeches, across all kinds. */
    default long globalMinGapMillis() {
        return DEFAULT_GLOBAL_MIN_GAP_MILLIS;
    }

    /** Minimum gap between two immediate speeches of the same kind. */
    default long sameKindMinGapMillis() {
        return DEFAULT_SAME_KIND_MIN_GAP_MILLIS;
    }

    /** Idle span after which an open aggregation window is closed and emitted. */
    default long aggregateWindowMillis() {
        return DEFAULT_AGGREGATE_WINDOW_MILLIS;
    }

    /** Number of folded occurrences needed before a window is emitted as one aggregate entry. */
    default int aggregateThreshold() {
        return DEFAULT_AGGREGATE_THRESHOLD;
    }
}
