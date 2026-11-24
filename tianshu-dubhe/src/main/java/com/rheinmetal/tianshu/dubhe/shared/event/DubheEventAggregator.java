package com.rheinmetal.tianshu.dubhe.shared.event;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Folds window-only ({@link DubheEventClass#FOLD}) occurrences into one counted line.
 *
 * <p>Only {@code FOLD} reaches this class. A spoken event is never folded, and a {@code SILENT} event appends every
 * occurrence as itself: those are facts memory should keep. A folded line is the opposite — it exists so the current
 * conversation knows "this happened a few times" without memory carrying forty-three pickup lines.</p>
 *
 * <p>A window is keyed by the kind and the rendered line's identity. That keeps two different subjects apart: four
 * different items picked up are four windows, never "item × 4".</p>
 *
 * <p>Below the configured threshold a window disappears instead of degrading to single lines: those occurrences are
 * exactly the noise this class exists to suppress (G38).</p>
 */
final class DubheEventAggregator {
    private final DubheEventSettings settings;
    private final Function<DubheEventText, String> renderer;
    private final Map<WindowKey, Window> windows = new LinkedHashMap<>();
    private final List<DubheEventAppend> closed = new ArrayList<>();

    DubheEventAggregator(DubheEventSettings settings) {
        this(settings, text -> "");
    }

    DubheEventAggregator(DubheEventSettings settings, Function<DubheEventText, String> renderer) {
        this.settings = settings;
        this.renderer = renderer == null ? text -> "" : renderer;
    }

    /**
     * Offers one off a fold-shaped occurrence.
     *
     * @return {@code true} when it was retained in a window; {@code false} when it has no fold shape and the caller
     *         must append its own window-only line
     */
    boolean offer(DubheEventDescriptor descriptor, String sentence, long nowMillis) {
        if (descriptor.fold() == DubheEventFold.NONE) {
            return false;
        }
        WindowKey key = new WindowKey(descriptor.kind(), identityOf(descriptor.text()));
        Window window = windows.get(key);
        if (window != null && isDue(window, nowMillis)) {
            // The window went idle before this occurrence arrived; close it instead of dropping it.
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
    List<DubheEventAppend> drainDue(long nowMillis) {
        List<DubheEventAppend> drained = new ArrayList<>(closed);
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
    List<DubheEventAppend> drainAll() {
        List<DubheEventAppend> drained = new ArrayList<>(closed);
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

    private List<DubheEventAppend> close(Window window) {
        int count = window.items.size();
        if (count < Math.max(2, settings.aggregateThreshold())) {
            // Below the threshold this is noise; it disappears rather than becoming one line per occurrence.
            return List.of();
        }
        String sentence = render(window, count);
        if (sentence.isBlank()) {
            // The fold template could not be worded; the window still leaves nothing behind.
            return List.of();
        }
        return List.of(new DubheEventAppend.Transient(window.key.kind(), count, sentence.trim()));
    }

    /** Renders the fold template of this window; a renderer that throws degrades to nothing. */
    private String render(Window window, int count) {
        Integer first = window.items.get(0).magnitude();
        Integer last = window.items.get(count - 1).magnitude();
        try {
            DubheEventText folded = DubheEventTexts.aggregate(window.key.kind(), window.fold, count, first, last);
            return DubheEventTexts.sanitize(renderer.apply(folded));
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    /**
     * What makes two occurrences "the same event": the template key plus its non-numeric arguments.
     *
     * <p>Numeric arguments are excluded on purpose: they are the magnitudes a fold template is supposed to summarise
     * ("lost 5 hearts" three times is one line). Text arguments are the subject — an item name, a dimension — and
     * those must keep windows apart.</p>
     */
    private static String identityOf(DubheEventText text) {
        StringBuilder identity = new StringBuilder();
        appendIdentity(text, identity, 0);
        return identity.toString();
    }

    private static void appendIdentity(DubheEventText text, StringBuilder out, int depth) {
        if (text == null || depth > DubheEventText.MAX_REF_DEPTH) {
            return;
        }
        out.append(text.key());
        for (DubheEventText.Arg arg : text.args()) {
            if (arg instanceof DubheEventText.Arg.Value value) {
                if (!isNumeric(value.text())) {
                    out.append('\u0000').append(value.text());
                }
            } else if (arg instanceof DubheEventText.Arg.Key reference) {
                // A language reference is part of what makes two occurrences different: "weather changed to
                // rain" and "to clear" must not share one count.
                out.append('\u0000').append(reference.key());
            } else if (arg instanceof DubheEventText.Arg.Nested nested) {
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
        private final DubheEventFold fold;
        private final List<Item> items = new ArrayList<>();
        private long lastAtMillis;

        private Window(WindowKey key, DubheEventFold fold) {
            this.key = key;
            this.fold = fold;
        }
    }
}