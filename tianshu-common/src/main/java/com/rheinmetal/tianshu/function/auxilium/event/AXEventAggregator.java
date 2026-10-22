package com.rheinmetal.tianshu.function.auxilium.event;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Folds repeated occurrences of one kind into a single history entry.
 *
 * <p>Frequency never changes a tier; it only changes granularity. The aggregator therefore holds
 * mid and low entries back until their window closes, then emits either one {@link
 * AXEventAppend.Aggregated} entry (when the window reached the threshold) or the retained {@link
 * AXEventAppend.Single} entries, so a non blacklisted event always ends up in the history.
 *
 * <p>High tier events are never aggregated: a folded "died 5 times" loses which death was which, and
 * high tier already has the global and per kind speech gaps as its throttle.
 */
final class AXEventAggregator {
    private final AXEventTierTable tierTable;
    private final AXEventSettings settings;
    private final Map<String, Window> windows = new LinkedHashMap<>();
    private final List<AXEventAppend> closed = new ArrayList<>();

    AXEventAggregator(AXEventTierTable tierTable, AXEventSettings settings) {
        this.tierTable = tierTable;
        this.settings = settings;
    }

    /**
     * Offers one occurrence for folding.
     *
     * @return {@code true} when the occurrence was retained in a window, {@code false} when the
     *         caller must append it immediately
     */
    boolean offer(AXEventDescriptor descriptor, AXEventTier tier, long nowMillis) {
        if (tier == AXEventTier.HIGH) {
            return false;
        }
        String kind = descriptor.kind();
        Integer magnitude = descriptor.payload().magnitude();
        if (tierTable.aggregateFormOf(kind) == AXEventAggregateForm.RANGE && magnitude == null) {
            // A range sentence needs numbers; without one the entry is appended as a plain line
            // instead of rendering an empty range to the player.
            return false;
        }
        Window window = windows.get(kind);
        if (window != null && isDue(window, nowMillis)) {
            // The window went idle before this occurrence arrived. Close it into the pending queue
            // instead of dropping it, so no retained occurrence is lost.
            windows.remove(kind);
            closed.addAll(close(window));
            window = null;
        }
        if (window == null) {
            window = new Window(kind);
            windows.put(kind, window);
        }
        window.items.add(new Item(descriptor.payload().text(), magnitude));
        window.lastAtMillis = nowMillis;
        return true;
    }

    /** Closes every window whose idle span reached the configured window length. */
    List<AXEventAppend> drainDue(long nowMillis) {
        List<AXEventAppend> drained = new ArrayList<>(closed);
        closed.clear();
        Iterator<Map.Entry<String, Window>> iterator = windows.entrySet().iterator();
        while (iterator.hasNext()) {
            Window window = iterator.next().getValue();
            if (!isDue(window, nowMillis)) {
                continue;
            }
            iterator.remove();
            drained.addAll(close(window));
        }
        return drained;
    }

    /** Closes every open window; call this before a turn is assembled so nothing is lost. */
    List<AXEventAppend> drainAll() {
        List<AXEventAppend> drained = new ArrayList<>(closed);
        closed.clear();
        for (Window window : windows.values()) {
            drained.addAll(close(window));
        }
        windows.clear();
        return drained;
    }

    int openWindowCount() {
        return windows.size();
    }

    private boolean isDue(Window window, long nowMillis) {
        return nowMillis - window.lastAtMillis >= settings.aggregateWindowMillis();
    }

    private List<AXEventAppend> close(Window window) {
        if (window.items.size() < Math.max(2, settings.aggregateThreshold())) {
            List<AXEventAppend> singles = new ArrayList<>(window.items.size());
            for (Item item : window.items) {
                singles.add(new AXEventAppend.Single(window.kind, item.text()));
            }
            return singles;
        }
        return List.of(aggregate(window));
    }

    private AXEventAppend.Aggregated aggregate(Window window) {
        int count = window.items.size();
        Integer first = window.items.get(0).magnitude();
        Integer last = window.items.get(count - 1).magnitude();
        Integer min = null;
        Integer max = null;
        for (Item item : window.items) {
            Integer magnitude = item.magnitude();
            if (magnitude == null) {
                continue;
            }
            min = min == null ? magnitude : Math.min(min, magnitude);
            max = max == null ? magnitude : Math.max(max, magnitude);
        }
        List<String> args = new ArrayList<>();
        args.add(Integer.toString(count));
        if (tierTable.aggregateFormOf(window.kind) == AXEventAggregateForm.RANGE) {
            args.add(first == null ? "" : first.toString());
            args.add(last == null ? "" : last.toString());
        }
        return new AXEventAppend.Aggregated(window.kind, count, first, last, min, max,
                AXEventText.aggregate(window.kind, args));
    }

    private record Item(String text, Integer magnitude) {
    }

    private static final class Window {
        private final String kind;
        private final List<Item> items = new ArrayList<>();
        private long lastAtMillis;

        private Window(String kind) {
            this.kind = kind;
        }
    }
}
