package com.rheinmetal.tianshu.function.presence.capture;

import com.rheinmetal.tianshu.function.presence.payload.PresenceWorldEventPayload;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Turns the arriving fact stream into one summary per kind per window.
 *
 * <p>Presence's cost must be bounded by "how many kinds happen", not "how many things happened". Every kind gets
 * one window; inside it, identical occurrences collapse into a single group with a count, and different
 * occurrences stay separate groups. A second of mining therefore leaves as one payload with one group, while
 * twenty dirt and sixty stone picked up in the same second leave as one payload with two groups.</p>
 *
 * <p>The window is a wall-clock second, advanced lazily: {@link #accept} checks whether the kind's window has
 * already run out, and {@link #drain()} closes every window that has. No tick is required for correctness, which is
 * what keeps the aggregator independent of the client loop.</p>
 *
 * <p>A window keeps at most {@link #MAX_GROUPS_PER_KIND} distinct groups. Beyond that, further groups are counted
 * and reported through {@link Drain#capped()} instead of growing the payload: a producer that makes every
 * occurrence unique must not be able to make the payload unbounded.</p>
 */
public final class PresenceWorldEventAggregator {
    /** One second, measured on the wall clock so the window survives a stalled tick. */
    public static final long WINDOW_MILLIS = 1_000L;
    /** Distinct occurrence groups kept per kind per window; the rest is counted, not stored. */
    public static final int MAX_GROUPS_PER_KIND = 32;

    private final LongSupplier clock;
    private final Map<String, Window> openWindows = new LinkedHashMap<>();
    private final List<PresenceWorldEventPayload> ready = new ArrayList<>();
    private final Map<String, Integer> cappedSinceDrain = new LinkedHashMap<>();

    public PresenceWorldEventAggregator() {
        this(System::currentTimeMillis);
    }

    public PresenceWorldEventAggregator(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Adds one arrived fact to its kind's window, closing a window that already ran out first. */
    public void accept(PresenceWorldEventPayload event) {
        if (event == null) {
            return;
        }
        long now = clock.getAsLong();
        String kind = event.eventType();
        Window window = openWindows.get(kind);
        if (window != null && window.expired(now)) {
            close(window);
            openWindows.remove(kind);
            window = null;
        }
        if (window == null) {
            window = new Window(now, event);
            openWindows.put(kind, window);
        }
        window.observe(event);
    }

    /**
     * Closes every window whose second has elapsed and returns what should be published.
     *
     * <p>Called from the client tick. It is the only place that produces payloads, so the aggregator itself never
     * touches the protocol sink.</p>
     */
    public Drain drain() {
        long now = clock.getAsLong();
        Iterator<Map.Entry<String, Window>> iterator = openWindows.entrySet().iterator();
        while (iterator.hasNext()) {
            Window window = iterator.next().getValue();
            if (window.expired(now)) {
                close(window);
                iterator.remove();
            }
        }
        if (ready.isEmpty() && cappedSinceDrain.isEmpty()) {
            return Drain.EMPTY;
        }
        Drain drained = new Drain(List.copyOf(ready), Map.copyOf(cappedSinceDrain));
        ready.clear();
        cappedSinceDrain.clear();
        return drained;
    }

    /** Drops every open window: facts of a world that ended must not be published into the next one. */
    public void reset() {
        openWindows.clear();
        ready.clear();
        cappedSinceDrain.clear();
    }

    private void close(Window window) {
        ready.add(window.toPayload());
        if (window.capped() > 0) {
            cappedSinceDrain.merge(window.kind(), window.capped(), Integer::sum);
        }
    }

    /**
     * What one drain produced: the payloads to publish, and — for kinds that overflowed their group cap — how many
     * occurrences were counted but not stored, so the caller can diagnose without the payload growing.
     */
    public record Drain(List<PresenceWorldEventPayload> payloads, Map<String, Integer> capped) {
        private static final Drain EMPTY = new Drain(List.of(), Map.of());

        public boolean isEmpty() {
            return payloads.isEmpty() && capped.isEmpty();
        }
    }

    /** One kind's window: the representative fact, the groups seen, and how many groups were over the cap. */
    private static final class Window {
        private final long startedAtMillis;
        private final PresenceWorldEventPayload first;
        private final Map<Map<String, String>, Integer> groups = new LinkedHashMap<>();
        private int capped;

        private Window(long startedAtMillis, PresenceWorldEventPayload first) {
            this.startedAtMillis = startedAtMillis;
            this.first = first;
        }

        private boolean expired(long nowMillis) {
            return nowMillis - startedAtMillis >= WINDOW_MILLIS;
        }

        private String kind() {
            return first.eventType();
        }

        private int capped() {
            return capped;
        }

        private void observe(PresenceWorldEventPayload event) {
            Map<String, String> group = event.values();
            Integer seen = groups.get(group);
            if (seen != null) {
                groups.put(group, seen + 1);
                return;
            }
            if (groups.size() >= MAX_GROUPS_PER_KIND) {
                capped++;
                return;
            }
            groups.put(group, 1);
        }

        private PresenceWorldEventPayload toPayload() {
            List<PresenceWorldEventPayload.Occurrence> occurrences = new ArrayList<>(groups.size());
            for (Map.Entry<Map<String, String>, Integer> group : groups.entrySet()) {
                occurrences.add(new PresenceWorldEventPayload.Occurrence(group.getValue(), group.getKey()));
            }
            return new PresenceWorldEventPayload(
                    null,
                    first.eventType(),
                    first.playerId(),
                    first.dimensionId(),
                    first.occurredAtMillis(),
                    occurrences
            );
        }
    }
}
