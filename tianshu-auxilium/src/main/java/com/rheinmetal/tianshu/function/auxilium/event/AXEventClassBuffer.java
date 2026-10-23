package com.rheinmetal.tianshu.function.auxilium.event;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/**
 * The two holding areas of the decision layer: the immediate queue and the deferred buffer.
 *
 * <p><b>Immediate queue</b> — an {@link AXEventClass#IMMEDIATE} event that arrived while the assistant was busy
 * and the player does not allow interruptions waits here. It is not spoken later on its own: the next thing that
 * happens is a user turn, and the queue is folded into the history just before that turn (see
 * {@link #takeForUserTurn()}).
 *
 * <p><b>Deferred buffer</b> — {@link AXEventClass#DEFERRED} events collect here until the cooldown window closes,
 * then they are spoken together as one voluntary turn and the window is re-drawn. The length is
 * {@code 120s + random(0..60s)}, re-drawn after every voluntary turn, so the turn never lands on a fixed beat.
 *
 * <p>Both areas share one rule: when the user speaks first, everything waiting is appended to the history in
 * arrival order and cleared. The order is the arrival order across <em>both</em> areas, not area by area, because
 * the history has to read the way the session happened.
 *
 * <p>Not thread safe: submitted from the game loop. The random source is injectable so a test can pin the window
 * without asserting a concrete value.
 */
public final class AXEventClassBuffer {

    /** One waiting occurrence: what to say, and its place in the arrival order. */
    public record Entry(String kind, String text, long arrivedAtMillis, long sequence) {
    }

    private final AXEventSettings settings;
    private final LongSupplier jitter;
    private final ArrayDeque<Entry> immediate = new ArrayDeque<>();
    private final List<Entry> deferred = new ArrayList<>();
    private long sequence;
    private long nextDeferredAtMillis = Long.MIN_VALUE;

    public AXEventClassBuffer(AXEventSettings settings) {
        this(settings, () -> ThreadLocalRandom.current().nextLong());
    }

    /**
     * @param jitter any long; it is folded into {@code [0, jitterRange)} so a test can pin the window without
     *               having to produce a value inside the range itself
     */
    public AXEventClassBuffer(AXEventSettings settings, LongSupplier jitter) {
        this.settings = settings == null ? AXEventSettings.DEFAULT : settings;
        this.jitter = jitter == null ? () -> 0L : jitter;
    }

    /** Queues an immediate event that may not be spoken now. */
    public void holdImmediate(String kind, String text, long nowMillis) {
        immediate.addLast(new Entry(kind, text, nowMillis, sequence++));
    }

    /** Adds a deferred event; the first one starts the cooldown window. */
    public void defer(String kind, String text, long nowMillis) {
        if (nextDeferredAtMillis == Long.MIN_VALUE || nowMillis >= nextDeferredAtMillis) {
            // Unarmed, or the deadline went by while nothing was buffered: a stale deadline would make the next
            // tick fire the moment an entry arrives, so the window restarts from this arrival instead.
            arm(nowMillis);
        }
        deferred.add(new Entry(kind, text, nowMillis, sequence++));
    }

    /**
     * Everything still waiting, in arrival order, and both areas cleared.
     *
     * <p>Called when the user speaks first: those lines belong to the history, in the order they happened, and the
     * deferred window starts over afterwards.
     */
    public List<Entry> takeForUserTurn() {
        List<Entry> drained = new ArrayList<>(immediate.size() + deferred.size());
        drained.addAll(immediate);
        drained.addAll(deferred);
        immediate.clear();
        deferred.clear();
        nextDeferredAtMillis = Long.MIN_VALUE;
        drained.sort(Comparator.comparingLong(Entry::sequence));
        return drained;
    }

    /**
     * The deferred batch, when the cooldown window has closed.
     *
     * @return the entries to speak as one voluntary turn, in arrival order; empty while the window is still open
     */
    public List<Entry> dueDeferred(long nowMillis) {
        if (nextDeferredAtMillis == Long.MIN_VALUE || nowMillis < nextDeferredAtMillis) {
            return List.of();
        }
        List<Entry> batch = new ArrayList<>(deferred);
        deferred.clear();
        arm(nowMillis);
        batch.sort(Comparator.comparingLong(Entry::sequence));
        return batch;
    }

    public int immediateSize() {
        return immediate.size();
    }

    public int deferredSize() {
        return deferred.size();
    }

    /** When the deferred window closes, or {@link Long#MIN_VALUE} while it is not armed. */
    public long nextDeferredAtMillis() {
        return nextDeferredAtMillis;
    }

    private void arm(long nowMillis) {
        nextDeferredAtMillis = nowMillis + cooldownMillis();
    }

    /** {@code base + [0, jitter)}; the modulo keeps an injected value inside the range. */
    private long cooldownMillis() {
        long range = Math.max(1L, settings.deferredCooldownJitterMillis());
        long offset = Math.floorMod(jitter.getAsLong(), range);
        return Math.max(0L, settings.deferredCooldownBaseMillis()) + offset;
    }
}
