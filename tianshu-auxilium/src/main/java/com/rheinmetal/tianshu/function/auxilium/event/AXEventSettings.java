package com.rheinmetal.tianshu.function.auxilium.event;

/**
 * Tunables of the event decision layer.
 *
 * <p>Every value here has a configuration name so a deployment can override it without touching code; nothing in
 * this layer may hardcode a window or a threshold.
 *
 * <ul>
 *   <li>{@link #frequencyWindowMillis()} - {@code ax.events.frequency.windowSec}, default 30s: the span a kind is
 *       counted in before the frequency step applies</li>
 *   <li>{@link #rareToNormalThreshold()} - {@code ax.events.frequency.rareToNormalThreshold}, default 3: occurrences
 *       inside the window that make a rare kind a normal one</li>
 *   <li>{@link #normalToFrequentThreshold()} - {@code ax.events.frequency.normalToFrequentThreshold}, default 6:
 *       occurrences inside the window that make a normal kind a frequent one</li>
 *   <li>{@link #deferredCooldownBaseMillis()} - {@code ax.events.deferred.cooldownBaseSec}, default 120s: the base
 *       of the lull after which the deferred buffer becomes one voluntary turn</li>
 *   <li>{@link #deferredCooldownJitterMillis()} - {@code ax.events.deferred.cooldownJitterSec}, default 60s: added
 *       at random on top of the base, re-drawn after every voluntary turn</li>
 *   <li>{@link #aggregateWindowMillis()} - {@code ax.events.aggregate.windowSec}, default 30s: idle span after
 *       which a fold window closes</li>
 *   <li>{@link #aggregateThreshold()} - {@code ax.events.aggregate.threshold}, default 3: occurrences needed
 *       before a fold window is emitted as one counted line</li>
 *   <li>{@link #busy()} - the host's "a turn is in flight" signal; with {@link #allowInterruption()} it decides
 *       whether an immediate event interrupts or waits</li>
 * </ul>
 */
public interface AXEventSettings {
    long DEFAULT_FREQUENCY_WINDOW_MILLIS = 30_000L;
    int DEFAULT_RARE_TO_NORMAL_THRESHOLD = 3;
    int DEFAULT_NORMAL_TO_FREQUENT_THRESHOLD = 6;
    long DEFAULT_DEFERRED_COOLDOWN_BASE_MILLIS = 120_000L;
    long DEFAULT_DEFERRED_COOLDOWN_JITTER_MILLIS = 60_000L;
    long DEFAULT_AGGREGATE_WINDOW_MILLIS = 30_000L;
    int DEFAULT_AGGREGATE_THRESHOLD = 3;

    AXEventSettings DEFAULT = new AXEventSettings() {
    };

    /** Span in which occurrences of one kind are counted for the frequency step. */
    default long frequencyWindowMillis() {
        return DEFAULT_FREQUENCY_WINDOW_MILLIS;
    }

    /** Occurrences inside the window that make a rare kind a normal one. */
    default int rareToNormalThreshold() {
        return DEFAULT_RARE_TO_NORMAL_THRESHOLD;
    }

    /** Occurrences inside the window that make a normal kind a frequent one. */
    default int normalToFrequentThreshold() {
        return DEFAULT_NORMAL_TO_FREQUENT_THRESHOLD;
    }

    /** Base lull before the deferred buffer is spoken as one voluntary turn. */
    default long deferredCooldownBaseMillis() {
        return DEFAULT_DEFERRED_COOLDOWN_BASE_MILLIS;
    }

    /** Random range added to the base lull, so the voluntary turn does not land on a fixed beat. */
    default long deferredCooldownJitterMillis() {
        return DEFAULT_DEFERRED_COOLDOWN_JITTER_MILLIS;
    }

    /** Idle span after which an open fold window is closed and emitted. */
    default long aggregateWindowMillis() {
        return DEFAULT_AGGREGATE_WINDOW_MILLIS;
    }

    /** Number of folded occurrences needed before a window is emitted as one counted entry. */
    default int aggregateThreshold() {
        return DEFAULT_AGGREGATE_THRESHOLD;
    }

    /**
     * Whether the assistant is busy right now (a turn is in flight, or it is still speaking).
     *
     * <p>Only the class {@link AXEventClass#IMMEDIATE} consults this: an event that arrives while the assistant is
     * busy either interrupts (see {@link #allowInterruption()}) or waits in the immediate queue. The default is
     * "never busy", which is what a host that has not wired a state yet sees.
     */
    default boolean busy() {
        return false;
    }

    /** The player's "may AX interrupt me" setting; ignored unless {@link #busy()} is true. */
    default boolean allowInterruption() {
        return true;
    }
}
