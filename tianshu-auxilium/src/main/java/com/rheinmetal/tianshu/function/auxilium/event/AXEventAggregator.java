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
 * AXEventAppend.Aggregated} entry (when the window reached the threshold and the producer's renderer
 * returned a sentence) or the retained {@link AXEventAppend.Single} entries, so a non blacklisted
 * event always ends up in the history.
 *
 * <p>A window is opened per source and kind. Two producers that happen to use the same kind keep
 * their own window, which is what lets a producer's own renderer describe only its own occurrences;
 * the slot never influences a tier, a blacklist flag or a decision.
 *
 * <p>High tier events are never aggregated: a folded "died 5 times" loses which death was which, and
 * high tier already has the global and per kind speech gaps as its throttle.
 */
final class AXEventAggregator {
    private final AXEventSettings settings;
    private final Map<WindowKey, Window> windows = new LinkedHashMap<>();
    private final List<AXEventAppend> closed = new ArrayList<>();

    AXEventAggregator(AXEventSettings settings) {
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
        AXEventAggregateRenderer renderer = descriptor.aggregateRenderer();
        if (renderer == null) {
            // No renderer means no folded sentence exists, and this layer never writes one itself.
            return false;
        }
        WindowKey key = new WindowKey(descriptor.sourceId(), descriptor.kind());
        Window window = windows.get(key);
        if (window != null && isDue(window, nowMillis)) {
            // The window went idle before this occurrence arrived. Close it into the pending queue
            // instead of dropping it, so no retained occurrence is lost.
            windows.remove(key);
            closed.addAll(close(window));
            window = null;
        }
        if (window == null) {
            window = new Window(key, renderer);
            windows.put(key, window);
        }
        window.items.add(new Item(descriptor.text(), descriptor.payload().magnitude()));
        window.lastAtMillis = nowMillis;
        return true;
    }

    /** Closes every window whose idle span reached the configured window length. */
    List<AXEventAppend> drainDue(long nowMillis) {
        List<AXEventAppend> drained = new ArrayList<>(closed);
        closed.clear();
        Iterator<Map.Entry<WindowKey, Window>> iterator = windows.entrySet().iterator();
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
            return singles(window);
        }
        int count = window.items.size();
        String sentence = render(window, count);
        if (sentence == null || sentence.isBlank()) {
            // The producer could not fold this window; the occurrences survive as single entries.
            return singles(window);
        }
        return List.of(new AXEventAppend.Aggregated(window.key.kind(), count, sentence.trim()));
    }

    /** Calls back into the producer; a renderer that throws degrades to single entries. */
    private String render(Window window, int count) {
        Integer first = window.items.get(0).magnitude();
        Integer last = window.items.get(count - 1).magnitude();
        try {
            return window.renderer.render(count, first, last);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private List<AXEventAppend> singles(Window window) {
        List<AXEventAppend> singles = new ArrayList<>(window.items.size());
        for (Item item : window.items) {
            singles.add(new AXEventAppend.Single(window.key.kind(), item.text()));
        }
        return singles;
    }

    private record WindowKey(String sourceId, String kind) {
    }

    private record Item(String text, Integer magnitude) {
    }

    private static final class Window {
        private final WindowKey key;
        private final AXEventAggregateRenderer renderer;
        private final List<Item> items = new ArrayList<>();
        private long lastAtMillis;

        private Window(WindowKey key, AXEventAggregateRenderer renderer) {
            this.key = key;
            this.renderer = renderer;
        }
    }
}
