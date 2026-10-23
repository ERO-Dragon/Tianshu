package com.rheinmetal.tianshu.function.auxilium.event;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Folds repeated silent occurrences into one counted history line.
 *
 * <p>Only {@link AXEventClass#SILENT} reaches this class: a spoken event is never folded, because a folded "said it
 * five times" loses which line was which and the speech channel already throttles itself. For a silent kind the
 * fold is the whole point — the model should learn that the thing happened, not read it forty-three times.
 *
 * <p>A window is keyed by the kind <em>and the rendered line</em>. That is what keeps two different payloads of the
 * same kind apart: four different items crafted are four windows with one occurrence each, never one window of
 * "item × 4". The key never influences a tier, a blacklist flag or a class.
 *
 * <p>The folded sentence is not a producer callback. The window carries the descriptor's {@link AXEventFold}, and at
 * close time the aggregator builds the fold template through {@link AXEventTexts#aggregate} and renders it with the
 * same renderer the single occurrences use, so the producer never contributes wording to a fold.
 */
final class AXEventAggregator {
    private final AXEventSettings settings;
    private final Function<AXEventText, String> renderer;
    private final Map<WindowKey, Window> windows = new LinkedHashMap<>();
    private final List<AXEventAppend> closed = new ArrayList<>();

    AXEventAggregator(AXEventSettings settings) {
        this(settings, text -> "");
    }

    AXEventAggregator(AXEventSettings settings, Function<AXEventText, String> renderer) {
        this.settings = settings;
        this.renderer = renderer == null ? text -> "" : renderer;
    }

    /**
     * Offers one occurrence for folding.
     *
     * @param sentence the already rendered sentence of this occurrence, kept so a window that never folds still
     *                 contributes readable single entries
     * @return {@code true} when the occurrence was retained in a window, {@code false} when the caller must append it
     *         immediately
     */
    boolean offer(AXEventDescriptor descriptor, String sentence, long nowMillis) {
        if (descriptor.fold() == AXEventFold.NONE) {
            // A one-off moment keeps its occurrences separate: there is no fold shape to word.
            return false;
        }
        WindowKey key = new WindowKey(descriptor.kind(), identityOf(descriptor.text()));
        Window window = windows.get(key);
        if (window != null && isDue(window, nowMillis)) {
            // The window went idle before this occurrence arrived. Close it into the pending queue
            // instead of dropping it, so no retained occurrence is lost.
            windows.remove(key);
            closed.addAll(close(window));
            window = null;
        }
        if (window == null) {
            window = new Window(key, descriptor.fold());
            windows.put(key, window);
        }
        window.items.add(new Item(sentence, descriptor.payload().magnitude()));
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
        if (sentence.isBlank()) {
            // The fold template could not be worded; the occurrences survive as single entries.
            return singles(window);
        }
        return List.of(new AXEventAppend.Aggregated(window.key.kind(), count, sentence.trim()));
    }

    /** Renders the fold template of this window; a renderer that throws degrades to single entries. */
    private String render(Window window, int count) {
        Integer first = window.items.get(0).magnitude();
        Integer last = window.items.get(count - 1).magnitude();
        try {
            AXEventText folded = AXEventTexts.aggregate(window.key.kind(), window.fold, count, first, last);
            return AXEventTexts.sanitize(renderer.apply(folded));
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private List<AXEventAppend> singles(Window window) {
        List<AXEventAppend> singles = new ArrayList<>(window.items.size());
        for (Item item : window.items) {
            singles.add(new AXEventAppend.Single(window.key.kind(), item.text()));
        }
        return singles;
    }

    /**
     * What makes two occurrences "the same event": the template key plus its non-numeric arguments.
     *
     * <p>Numeric arguments are excluded on purpose. They are the magnitudes a {@link AXEventFold#RANGE} or
     * {@link AXEventFold#COUNT} window is supposed to summarise ("lost 5 hearts" three times is one line), so treating
     * them as identity would make every occurrence its own window and every fold template unreachable. Text arguments
     * are the subject — an item name, a dimension, a player — and those must keep the windows apart: four different
     * items crafted are four lines, never "item × 4".
     *
     * <p>Nested templates contribute their own key and non-numeric arguments for the same reason.
     */
    private static String identityOf(AXEventText text) {
        StringBuilder identity = new StringBuilder();
        appendIdentity(text, identity, 0);
        return identity.toString();
    }

    private static void appendIdentity(AXEventText text, StringBuilder out, int depth) {
        if (text == null || depth > AXEventText.MAX_REF_DEPTH) {
            return;
        }
        out.append(text.key());
        for (AXEventText.Arg arg : text.args()) {
            if (arg instanceof AXEventText.Arg.Value value) {
                if (!isNumeric(value.text())) {
                    out.append('\u0000').append(value.text());
                }
            } else if (arg instanceof AXEventText.Arg.Nested nested) {
                out.append('\u0000');
                appendIdentity(nested.text(), out, depth + 1);
            }
        }
    }

    private static boolean isNumeric(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            Double.parseDouble(value.trim());
            return true;
        } catch (NumberFormatException notANumber) {
            return false;
        }
    }

    /** Kind plus the occurrence's identity: two different subjects must never share one count. */
    private record WindowKey(String kind, String identity) {
    }

    private record Item(String text, Integer magnitude) {
    }

    private static final class Window {
        private final WindowKey key;
        private final AXEventFold fold;
        private final List<Item> items = new ArrayList<>();
        private long lastAtMillis;

        private Window(WindowKey key, AXEventFold fold) {
            this.key = key;
            this.fold = fold;
        }
    }
}
